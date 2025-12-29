package com.traderecon.tradepublisher.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobStatus {

    private String jobId;

    private JobStatusEnum status;

    private Integer tradesGenerated;

    private Integer tradesPublished;

    private Integer failedPublishes;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime endTime;

    private String duration;

    private String throughput;

    private String errorMessage;

    public void calculateDuration() {
        if (startTime != null && endTime != null) {
            long seconds = java.time.Duration.between(startTime, endTime).getSeconds();
            long minutes = seconds / 60;
            long remainingSeconds = seconds % 60;
            this.duration = String.format("%dm %ds", minutes, remainingSeconds);
        }
    }

    public void calculateThroughput() {
        if (startTime != null && endTime != null && tradesGenerated != null && tradesGenerated > 0) {
            long seconds = java.time.Duration.between(startTime, endTime).getSeconds();
            if (seconds > 0) {
                long tradesPerSecond = tradesGenerated / seconds;
                this.throughput = tradesPerSecond + " trades/sec";
            }
        }
    }
}