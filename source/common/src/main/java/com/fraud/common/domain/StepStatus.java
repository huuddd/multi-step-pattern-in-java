package com.fraud.common.domain;

/**
 * Status of a pipeline step execution.
 */
public enum StepStatus {
    RUNNING,
    DONE,
    FAILED,
    RETRIED,
    SKIPPED
}
