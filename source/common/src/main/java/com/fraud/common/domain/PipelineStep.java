package com.fraud.common.domain;

/**
 * Steps in the fraud detection pipeline.
 */
public enum PipelineStep {
    INGEST,
    FEATURE,
    MODEL,
    RULE,
    DECISION,
    WEBHOOK
}
