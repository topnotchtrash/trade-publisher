package com.traderecon.tradepublisher.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class KafkaProducerService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
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

        this.publishedCounter = Counter.builder("trades.published.total")
                .description("Total number of trades successfully published to Kafka")
                .register(meterRegistry);

        this.failedCounter = Counter.builder("trades.publish.failed.total")
                .description("Total number of trades that failed to publish to Kafka")
                .register(meterRegistry);

        this.publishLatencyTimer = Timer.builder("kafka.publish.latency")
                .description("Latency of publishing trades to Kafka")
                .register(meterRegistry);
    }

    public int publishBatch(List<?> trades) {
        int successCount = 0;
        int failureCount = 0;

        log.info("Publishing batch of {} trades to Kafka", trades.size());

        for (Object trade : trades) {
            try {
                boolean success = publishTrade(trade);
                if (success) {
                    successCount++;
                } else {
                    failureCount++;
                }
            } catch (Exception e) {
                log.error("Unexpected error publishing trade", e);
                failureCount++;
                failedCounter.increment();
            }
        }

        log.info("Batch publish completed - Success: {}, Failed: {}", successCount, failureCount);
        return successCount;
    }

    private boolean publishTrade(Object trade) {
        return publishLatencyTimer.record(() -> {
            try {
                String tradeJson = objectMapper.writeValueAsString(trade);

                String partitionKey = extractPartitionKey(trade);

                CompletableFuture<SendResult<String, String>> future =
                        kafkaTemplate.send(tradeInputTopic, partitionKey, tradeJson);

                future.whenComplete((result, ex) -> {
                    if (ex == null) {
                        onPublishSuccess(trade, result);
                    } else {
                        onPublishFailure(trade, ex);
                    }
                });

                return true;

            } catch (JsonProcessingException e) {
                log.error("Failed to serialize trade to JSON: {}", trade, e);
                failedCounter.increment();
                return false;
            } catch (Exception e) {
                log.error("Failed to publish trade to Kafka: {}", trade, e);
                failedCounter.increment();
                return false;
            }
        });
    }

    private void onPublishSuccess(Object trade, SendResult<String, String> result) {
        publishedCounter.increment();

        if (log.isDebugEnabled()) {
            log.debug("Successfully published trade to partition: {}, offset: {}",
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        }
    }

    private void onPublishFailure(Object trade, Throwable ex) {
        failedCounter.increment();
        log.error("Failed to publish trade to Kafka", ex);
    }

    private String extractPartitionKey(Object trade) {
        try {
            java.lang.reflect.Method getTradeTypeMethod = trade.getClass().getMethod("getTradeType");
            Object tradeType = getTradeTypeMethod.invoke(trade);
            return tradeType != null ? tradeType.toString() : "UNKNOWN";
        } catch (Exception e) {
            log.warn("Could not extract trade type for partitioning, using default", e);
            return "DEFAULT";
        }
    }
}