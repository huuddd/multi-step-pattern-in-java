package com.fraud.common.domain;

/**
 * Status of a pipeline step execution.
 */
public enum StepStatus {
    STARTED,
    RUNNING,
    DONE,
    FAILED,
    RETRIED,
    SKIPPED
}
