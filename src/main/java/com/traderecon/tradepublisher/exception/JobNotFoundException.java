package com.traderecon.tradepublisher.exception;

public class JobNotFoundException extends RuntimeException{

    private final String jobId;
    public JobNotFoundException(String jobId){
        super(String.format("Job not found: %s", jobId));
        this.jobId = jobId;
    }


    public String getJobId() {
        return jobId;
    }
}