package com.traderecon.tradepublisher.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.annapurna.model.Trade;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class KafkaProducerService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Timer publishLatencyTimer;

    @Value("${kafka.topic.trade-input}")
    private String tradeInputTopic;

    public KafkaProducerService(KafkaTemplate<String, String> kafkaTemplate,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;

        this.publishedCounter = Counter.builder("trades.published.total")
                .description("Total trades successfully acknowledged by Kafka")
                .register(meterRegistry);

        this.failedCounter = Counter.builder("trades.publish.failed.total")
                .description("Total trades that failed to reach Kafka")
                .register(meterRegistry);

        this.publishLatencyTimer = Timer.builder("kafka.publish.latency")
                .description("Actual time from send start to Kafka acknowledgement")
                .register(meterRegistry);
    }

    /**
     * Publishes a batch and waits for all async calls to complete
     * to provide an accurate success count.
     */
    public int publishBatch(List<Trade> trades) {
        if (trades == null || trades.isEmpty()) return 0;

        log.info("Publishing batch of {} trades to Kafka", trades.size());
        AtomicInteger successCount = new AtomicInteger(0);

        List<CompletableFuture<SendResult<String, String>>> futures = trades.stream()
                .map(trade -> publishTrade(trade, successCount))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("Batch publish completed - Success: {}, Failed: {}",
                successCount.get(), trades.size() - successCount.get());

        return successCount.get();
    }

    private CompletableFuture<SendResult<String, String>> publishTrade(Trade trade, AtomicInteger successCount) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            String tradeJson = objectMapper.writeValueAsString(trade);
            String partitionKey = trade.getTradeType().name();

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(tradeInputTopic, partitionKey, tradeJson);

            // Use thenApply to ensure successCount is updated BEFORE future completes
            return future.handle((result, ex) -> {
                sample.stop(publishLatencyTimer);

                if (ex == null) {
                    onPublishSuccess(trade, result);
                    successCount.incrementAndGet();
                    return result;
                } else {
                    onPublishFailure(trade, ex);
                    throw new RuntimeException(ex);
                }
            });

        } catch (JsonProcessingException e) {
            sample.stop(publishLatencyTimer);
            log.error("Failed to serialize trade: {}", trade.getTradeId(), e);
            failedCounter.increment();
            return CompletableFuture.failedFuture(e);
        }
    }

    private String extractPartitionKey(Object trade, Method method) {
        if (method == null) return "DEFAULT";
        try {
            Object tradeType = method.invoke(trade);
            return tradeType != null ? tradeType.toString() : "UNKNOWN";
        } catch (Exception e) {
            return "ERROR";
        }
    }
    private void onPublishSuccess(Trade trade, SendResult<String, String> result) {
        publishedCounter.increment();

        if (log.isDebugEnabled()) {
            log.debug("Successfully published trade {} to partition: {}, offset: {}",
                    trade.getTradeId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        }
    }

    private void onPublishFailure(Trade trade, Throwable ex) {
        failedCounter.increment();
        log.error("Failed to publish trade {} to Kafka", trade.getTradeId(), ex);
    }
}