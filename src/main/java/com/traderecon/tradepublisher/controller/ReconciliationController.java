package com.traderecon.tradepublisher.controller;

import com.traderecon.tradepublisher.model.JobStatus;
import com.traderecon.tradepublisher.model.ReconciliationRequest;
import com.traderecon.tradepublisher.model.ReconciliationResponse;
import com.traderecon.tradepublisher.service.JobTrackingService;
import com.traderecon.tradepublisher.service.TradeGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@RestController
@RequestMapping("/api/reconciliation")
@RequiredArgsConstructor
@Slf4j
public class ReconciliationController {

    private final TradeGenerationService tradeGenerationService;
    private final JobTrackingService jobTrackingService;

    @PostMapping("/start")
    public ResponseEntity<ReconciliationResponse> startReconciliation(
            @Valid @RequestBody ReconciliationRequest request) {

        String jobId = generateJobId();

        log.info("Starting reconciliation job: {}", jobId);
        log.info("Trade count: {}, Trade distribution: {}, Profile distribution: {}",
                request.getTradeCount(),
                request.getTradeDistribution(),
                request.getProfileDistribution());

        jobTrackingService.createJob(jobId, request);

        tradeGenerationService.generateAndPublishTrades(request, jobId);

        ReconciliationResponse response = ReconciliationResponse.builder()
                .jobId(jobId)
                .status("IN_PROGRESS")
                .startTime(LocalDateTime.now())
                .estimatedCompletion(estimateCompletion(request.getTradeCount()))
                .message("Trade generation started")
                .build();

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<JobStatus> getJobStatus(@PathVariable String jobId) {
        log.info("Fetching status for job: {}", jobId);

        JobStatus status = jobTrackingService.getJobStatus(jobId);

        return ResponseEntity.ok(status);
    }

    private String generateJobId() {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        return String.format("rec-%s-%s", timestamp, uniqueId);
    }

    private LocalDateTime estimateCompletion(int tradeCount) {
        int estimatedSeconds = tradeCount / 3300 + 5;
        return LocalDateTime.now().plusSeconds(estimatedSeconds);
    }
}