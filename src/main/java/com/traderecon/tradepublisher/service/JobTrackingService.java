package com.traderecon.tradepublisher.service;

import com.traderecon.tradepublisher.exception.JobNotFoundException;
import com.traderecon.tradepublisher.model.JobStatus;
import com.traderecon.tradepublisher.model.JobStatusEnum;
import com.traderecon.tradepublisher.model.ReconciliationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobTrackingService {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${job.ttl-hours:24}")
    private Integer ttlHours;

    private static final String JOB_KEY_PREFIX = "job:";

    public void createJob(String jobId, ReconciliationRequest request) {
        JobStatus jobStatus = JobStatus.builder()
                .jobId(jobId)
                .status(JobStatusEnum.IN_PROGRESS)
                .tradesGenerated(0)
                .tradesPublished(0)
                .failedPublishes(0)
                .startTime(LocalDateTime.now())
                .build();

        String key = getJobKey(jobId);
        redisTemplate.opsForValue().set(key, jobStatus, ttlHours, TimeUnit.HOURS);

        log.info("Created job in Redis: {} with TTL {} hours", jobId, ttlHours);
    }

    public void updateStatus(String jobId, JobStatusEnum status) {
        JobStatus jobStatus = getJobStatus(jobId);
        jobStatus.setStatus(status);

        if (status == JobStatusEnum.COMPLETED || status == JobStatusEnum.FAILED) {
            jobStatus.setEndTime(LocalDateTime.now());
            jobStatus.calculateDuration();
            jobStatus.calculateThroughput();
        }

        saveJobStatus(jobId, jobStatus);

        log.info("Updated job {} status to: {}", jobId, status);
    }

    public void updateTradesGenerated(String jobId, int tradesGenerated) {
        JobStatus jobStatus = getJobStatus(jobId);
        jobStatus.setTradesGenerated(tradesGenerated);
        saveJobStatus(jobId, jobStatus);

        log.info("Job {} - Trades generated: {}", jobId, tradesGenerated);
    }

    public void updateProgress(String jobId, int delta, int totalTrades) {
        String key = "JOB_STATUS:" + jobId;
        String hashKey = "tradesPublished";

        // This forces Redis to treat the value as a long for the increment operation
        Long newTotal = redisTemplate.opsForHash().increment(key, hashKey, (long) delta);

        if (newTotal != null && (newTotal % 10000 == 0 || newTotal >= totalTrades)) {
            log.info("Job {} progress: {}/{} trades published ({}%)",
                    jobId, newTotal, totalTrades, (newTotal * 100) / totalTrades);
        }
    }
    public void incrementFailedPublishes(String jobId) {
        JobStatus jobStatus = getJobStatus(jobId);
        jobStatus.setFailedPublishes(jobStatus.getFailedPublishes() + 1);
        saveJobStatus(jobId, jobStatus);

        log.warn("Job {} - Failed publish count: {}", jobId, jobStatus.getFailedPublishes());
    }

    public void setErrorMessage(String jobId, String errorMessage) {
        JobStatus jobStatus = getJobStatus(jobId);
        jobStatus.setErrorMessage(errorMessage);
        jobStatus.setStatus(JobStatusEnum.FAILED);
        jobStatus.setEndTime(LocalDateTime.now());
        jobStatus.calculateDuration();

        saveJobStatus(jobId, jobStatus);

        log.error("Job {} failed with error: {}", jobId, errorMessage);
    }

    public JobStatus getJobStatus(String jobId) {
        String key = getJobKey(jobId);
        Object result = redisTemplate.opsForValue().get(key);

        if (result == null) {
            log.error("Job not found in Redis: {}", jobId);
            throw new JobNotFoundException(jobId);
        }

        return (JobStatus) result;
    }

    public void deleteJob(String jobId) {
        String key = getJobKey(jobId);
        redisTemplate.delete(key);
        log.info("Deleted job from Redis: {}", jobId);
    }

    private void saveJobStatus(String jobId, JobStatus jobStatus) {
        String key = getJobKey(jobId);
        redisTemplate.opsForValue().set(key, jobStatus, ttlHours, TimeUnit.HOURS);
    }

    private String getJobKey(String jobId) {
        return JOB_KEY_PREFIX + jobId;
    }
}