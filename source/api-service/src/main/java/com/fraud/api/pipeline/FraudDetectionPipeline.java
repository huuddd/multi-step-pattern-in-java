package com.fraud.api.pipeline;

import com.fraud.api.domain.Payment;
import com.fraud.api.domain.RiskEvent;
import com.fraud.api.pipeline.steps.*;
import com.fraud.api.repository.RiskEventRepository;
import com.fraud.common.domain.StepStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Orchestrates the fraud detection pipeline.
 * Variant A: All steps run sequentially in the same thread.
 */
@Component
@Slf4j
public class FraudDetectionPipeline {

    private final List<PipelineStep> steps;
    private final RiskEventRepository riskEventRepository;
    private final MeterRegistry meterRegistry;

    public FraudDetectionPipeline(
            IngestStep ingestStep,
            FeatureStep featureStep,
            ModelStep modelStep,
            RuleStep ruleStep,
            DecisionStep decisionStep,
            RiskEventRepository riskEventRepository,
            MeterRegistry meterRegistry) {
        this.steps = List.of(ingestStep, featureStep, modelStep, ruleStep, decisionStep);
        this.riskEventRepository = riskEventRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Execute the full pipeline for a payment.
     */
    public PipelineContext execute(Payment payment) {
        log.info("Starting pipeline for payment: {}", payment.getPaymentId());
        
        PipelineContext context = PipelineContext.builder()
                .payment(payment)
                .build();

        for (PipelineStep step : steps) {
            context = executeStep(step, context);
        }

        log.info("Pipeline completed for payment: {}, decision: {}", 
                payment.getPaymentId(), context.getDecision());
        
        return context;
    }

    private PipelineContext executeStep(PipelineStep step, PipelineContext context) {
        String stepName = step.getStepName();
        String paymentId = context.getPayment().getPaymentId();
        
        // Check idempotency - skip if already done
        com.fraud.common.domain.PipelineStep stepEnum = com.fraud.common.domain.PipelineStep.valueOf(stepName);
        if (riskEventRepository.existsByPaymentIdAndStepAndStatus(paymentId, stepEnum, StepStatus.DONE)) {
            log.debug("Step {} already completed for payment {}, skipping", stepName, paymentId);
            return context;
        }

        // Record step start
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            // Execute step
            context = step.execute(context);
            
            // Record success
            RiskEvent doneEvent = RiskEvent.done(paymentId, stepEnum, Map.of("success", true));
            riskEventRepository.save(doneEvent);
            
            // Record metrics
            sample.stop(Timer.builder("pipeline.step.duration")
                    .tag("step", stepName)
                    .tag("status", "success")
                    .register(meterRegistry));
            
            return context;
            
        } catch (Exception e) {
            log.error("Step {} failed for payment {}: {}", stepName, paymentId, e.getMessage(), e);
            
            // Record failure
            RiskEvent failedEvent = RiskEvent.failed(paymentId, stepEnum, e.getMessage());
            riskEventRepository.save(failedEvent);
            
            // Record metrics
            sample.stop(Timer.builder("pipeline.step.duration")
                    .tag("step", stepName)
                    .tag("status", "error")
                    .register(meterRegistry));
            
            throw e;
        }
    }
}
