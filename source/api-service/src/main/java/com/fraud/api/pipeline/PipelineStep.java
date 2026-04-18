package com.fraud.api.pipeline;

/**
 * Interface for pipeline steps.
 */
public interface PipelineStep {
    
    /**
     * Execute this step of the pipeline.
     * @param context The pipeline context
     * @return The context (possibly modified)
     */
    PipelineContext execute(PipelineContext context);
    
    /**
     * Get the name of this step for logging/metrics.
     */
    String getStepName();
}
