package com.traderecon.tradepublisher.service;

import com.traderecon.tradepublisher.model.JobStatusEnum;
import com.traderecon.tradepublisher.model.ReconciliationRequest;
import io.annapurna.Annapurna;
import io.annapurna.model.Trade;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeGenerationService {

    private final KafkaProducerService kafkaProducerService;
    private final JobTrackingService jobTrackingService;
    private final MeterRegistry meterRegistry;

    @Value("${batch.size:100}")
    private Integer batchSize;

    @Async
    public void generateAndPublishTrades(ReconciliationRequest request, String jobId) {
        LocalDateTime startTime = LocalDateTime.now();

        log.info("Starting trade generation for job: {}", jobId);
        log.info("Trade count: {}, Batch size: {}", request.getTradeCount(), batchSize);

        try {
            List<Trade> trades = generateTrades(request);

            jobTrackingService.updateTradesGenerated(jobId, trades.size());
            log.info("Generated {} trades for job: {}", trades.size(), jobId);

            int publishedCount = publishTradesInBatches(trades, jobId);

            jobTrackingService.updateStatus(jobId, JobStatusEnum.COMPLETED);

            logCompletionMetrics(jobId, startTime, trades.size(), publishedCount);

        } catch (Exception e) {
            log.error("Job {} failed with error", jobId, e);
            jobTrackingService.setErrorMessage(jobId, e.getMessage());
            recordFailureMetric();
        }
    }

    private List<Trade> generateTrades(ReconciliationRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            Map<String, Integer> tradeDistribution = request.getTradeDistribution();
            Map<String, Integer> profileDistribution = request.getProfileDistribution();

            List<Trade> trades = Annapurna.builder()
                    .tradeTypes()
                    .interestRateSwap(tradeDistribution.getOrDefault("INTEREST_RATE_SWAP", 0))
                    .equitySwap(tradeDistribution.getOrDefault("EQUITY_SWAP", 0))
                    .fxForward(tradeDistribution.getOrDefault("FX_FORWARD", 0))
                    .option(tradeDistribution.getOrDefault("EQUITY_OPTION", 0))
                    .cds(tradeDistribution.getOrDefault("CREDIT_DEFAULT_SWAP", 0))
                    .dataProfile()
                    .clean(profileDistribution.getOrDefault("CLEAN", 0))
                    .edgeCase(profileDistribution.getOrDefault("EDGE_CASE", 0))
                    .stress(profileDistribution.getOrDefault("STRESS", 0))
                    .count(request.getTradeCount())
                    .parallelism(8)
                    .build()
                    .generate();

            sample.stop(Timer.builder("trade.generation.duration")
                    .description("Time taken to generate trades")
                    .register(meterRegistry));

            Counter.builder("trades.generated.total")
                    .description("Total trades generated")
                    .register(meterRegistry)
                    .increment(trades.size());

            return trades;

        } catch (Exception e) {
            log.error("Failed to generate trades using Annapurna library", e);
            throw new RuntimeException("Trade generation failed: " + e.getMessage(), e);
        }
    }

    private int publishTradesInBatches(List<Trade> trades, String jobId) {
        AtomicInteger totalPublished = new AtomicInteger(0); // Thread-safe counter
        int totalTradeCount = trades.size();
        int batchCount = (int) Math.ceil((double) totalTradeCount / batchSize);

        log.info("Starting Parallel Publishing: {} trades in {} batches", totalTradeCount, batchCount);

        // Create a range of indices for the batches
        IntStream.range(0, batchCount).parallel().forEach(batchIndex -> {
            int start = batchIndex * batchSize;
            int end = Math.min(start + batchSize, totalTradeCount);

            List<Trade> batch = trades.subList(start, end);

            // This call is now happening on multiple threads simultaneously
            int publishedInBatch = kafkaProducerService.publishBatch(batch);

            int currentTotal = totalPublished.addAndGet(publishedInBatch);

            // Update progress in Redis (shared state)
            jobTrackingService.updateProgress(jobId, publishedInBatch, totalTradeCount);

            if (batchIndex % 10 == 0) {
                log.info("Batch {}/{} processed by thread: {}",
                        batchIndex + 1, batchCount, Thread.currentThread().getName());
            }
        });

        log.info("Completed parallel publishing. Total: {} for job: {}", totalPublished.get(), jobId);
        return totalPublished.get();
    }
    private void logCompletionMetrics(String jobId, LocalDateTime startTime, int generated, int published) {
        Duration duration = Duration.between(startTime, LocalDateTime.now());
        long seconds = duration.getSeconds();
        long throughput = seconds > 0 ? generated / seconds : 0;

        log.info("Job {} completed successfully", jobId);
        log.info("Total generated: {}, Total published: {}, Duration: {}s, Throughput: {} trades/sec",
                generated, published, seconds, throughput);

        Counter.builder("jobs.completed.total")
                .description("Total jobs completed")
                .register(meterRegistry)
                .increment();
    }

    private void recordFailureMetric() {
        Counter.builder("jobs.failed.total")
                .description("Total jobs failed")
                .register(meterRegistry)
                .increment();
    }
}