package com.fraud.api.pipeline;

import com.fraud.api.domain.Payment;
import com.fraud.api.domain.RiskEvent;
import com.fraud.api.pipeline.steps.AsyncModelStep;
import com.fraud.api.pipeline.steps.DecisionStep;
import com.fraud.api.pipeline.steps.FeatureStep;
import com.fraud.api.pipeline.steps.IngestStep;
import com.fraud.api.pipeline.steps.RuleStep;
import com.fraud.api.repository.RiskEventRepository;
import com.fraud.common.domain.StepStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Variant B: Pipeline with async model scoring in thread pool.
 * 
 * Flow:
 * 1. Ingest (sync) → 2. Feature (sync) → 3. Model (ASYNC) → 4. Rule (sync) → 5. Decision (sync)
 * 
 * Model scoring runs in dedicated ThreadPoolExecutor for:
 * - Better CPU utilization
 * - Backpressure via bounded queue
 * - Isolation from request thread
 */
@Component("asyncPipeline")
@Slf4j
public class AsyncFraudDetectionPipeline {

    private final IngestStep ingestStep;
    private final FeatureStep featureStep;
    private final AsyncModelStep asyncModelStep;
    private final RuleStep ruleStep;
    private final DecisionStep decisionStep;
    private final RiskEventRepository riskEventRepository;
    private final MeterRegistry meterRegistry;
    private final long modelTimeoutMs;

    public AsyncFraudDetectionPipeline(
            IngestStep ingestStep,
            FeatureStep featureStep,
            AsyncModelStep asyncModelStep,
            RuleStep ruleStep,
            DecisionStep decisionStep,
            RiskEventRepository riskEventRepository,
            MeterRegistry meterRegistry,
            @Value("${pipeline.model.timeout-ms:5000}") long modelTimeoutMs) {
        this.ingestStep = ingestStep;
        this.featureStep = featureStep;
        this.asyncModelStep = asyncModelStep;
        this.ruleStep = ruleStep;
        this.decisionStep = decisionStep;
        this.riskEventRepository = riskEventRepository;
        this.meterRegistry = meterRegistry;
        this.modelTimeoutMs = modelTimeoutMs;
    }

    /**
     * Execute pipeline with async model scoring.
     */
    public PipelineContext execute(Payment payment) {
        String paymentId = payment.getPaymentId();
        log.info("Starting async pipeline for payment: {}", paymentId);
        
        Timer.Sample pipelineSample = Timer.start(meterRegistry);
        
        PipelineContext context = PipelineContext.builder()
                .payment(payment)
                .build();

        try {
            // Step 1: Ingest (sync)
            context = executeSyncStep(ingestStep, context);
            
            // Step 2: Feature extraction (sync)
            context = executeSyncStep(featureStep, context);
            
            // Step 3: Model scoring (ASYNC in thread pool)
            context = executeAsyncModelStep(context);
            
            // Step 4: Rule evaluation (sync)
            context = executeSyncStep(ruleStep, context);
            
            // Step 5: Decision (sync)
            context = executeSyncStep(decisionStep, context);
            
            log.info("Async pipeline completed for payment: {}, decision: {}", 
                    paymentId, context.getDecision());
            
            pipelineSample.stop(Timer.builder("pipeline.total.duration")
                    .tag("variant", "async")
                    .tag("status", "success")
                    .register(meterRegistry));
            
            return context;
            
        } catch (Exception e) {
            log.error("Async pipeline failed for payment {}: {}", paymentId, e.getMessage(), e);
            
            pipelineSample.stop(Timer.builder("pipeline.total.duration")
                    .tag("variant", "async")
                    .tag("status", "error")
                    .register(meterRegistry));
            
            throw e;
        }
    }

    /**
     * Execute a synchronous pipeline step.
     */
    private PipelineContext executeSyncStep(PipelineStep step, PipelineContext context) {
        String stepName = step.getStepName();
        String paymentId = context.getPayment().getPaymentId();
        
        // Check idempotency
        com.fraud.common.domain.PipelineStep stepEnum = 
                com.fraud.common.domain.PipelineStep.valueOf(stepName);
        if (riskEventRepository.existsByPaymentIdAndStepAndStatus(paymentId, stepEnum, StepStatus.DONE)) {
            log.debug("Step {} already completed for payment {}, skipping", stepName, paymentId);
            return context;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            context = step.execute(context);
            
            RiskEvent event = RiskEvent.done(paymentId, stepEnum, Map.of("success", true));
            riskEventRepository.save(event);
            
            sample.stop(Timer.builder("pipeline.step.duration")
                    .tag("step", stepName)
                    .tag("variant", "async")
                    .tag("status", "success")
                    .register(meterRegistry));
            
            return context;
            
        } catch (Exception e) {
            RiskEvent event = RiskEvent.failed(paymentId, stepEnum, e.getMessage());
            riskEventRepository.save(event);
            
            sample.stop(Timer.builder("pipeline.step.duration")
                    .tag("step", stepName)
                    .tag("variant", "async")
                    .tag("status", "error")
                    .register(meterRegistry));
            
            throw e;
        }
    }

    /**
     * Execute model scoring asynchronously in thread pool.
     */
    private PipelineContext executeAsyncModelStep(PipelineContext context) {
        String paymentId = context.getPayment().getPaymentId();
        
        // Check idempotency
        com.fraud.common.domain.PipelineStep stepEnum = com.fraud.common.domain.PipelineStep.MODEL;
        if (riskEventRepository.existsByPaymentIdAndStepAndStatus(paymentId, stepEnum, StepStatus.DONE)) {
            log.debug("MODEL step already completed for payment {}, skipping", paymentId);
            return context;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            // Submit to thread pool and wait for result
            CompletableFuture<BigDecimal> scoreFuture = asyncModelStep.scoreAsync(context);
            
            // Wait with timeout
            BigDecimal riskScore = scoreFuture.get(modelTimeoutMs, TimeUnit.MILLISECONDS);
            context.setRiskScore(riskScore);
            
            // Record success
            RiskEvent event = RiskEvent.done(paymentId, stepEnum, Map.of(
                    "risk_score", riskScore.toString(),
                    "async", true
            ));
            riskEventRepository.save(event);
            
            sample.stop(Timer.builder("pipeline.step.duration")
                    .tag("step", "MODEL")
                    .tag("variant", "async")
                    .tag("status", "success")
                    .register(meterRegistry));
            
            return context;
            
        } catch (TimeoutException e) {
            log.warn("Model scoring timeout for payment {}, using default score", paymentId);
            
            // Use default score on timeout
            context.setRiskScore(BigDecimal.valueOf(0.5));
            
            RiskEvent event = RiskEvent.done(paymentId, stepEnum, Map.of(
                    "risk_score", "0.5",
                    "timeout", true
            ));
            riskEventRepository.save(event);
            
            sample.stop(Timer.builder("pipeline.step.duration")
                    .tag("step", "MODEL")
                    .tag("variant", "async")
                    .tag("status", "timeout")
                    .register(meterRegistry));
            
            return context;
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Model scoring interrupted", e);
            
        } catch (ExecutionException e) {
            RiskEvent event = RiskEvent.failed(paymentId, stepEnum, e.getCause().getMessage());
            riskEventRepository.save(event);
            
            sample.stop(Timer.builder("pipeline.step.duration")
                    .tag("step", "MODEL")
                    .tag("variant", "async")
                    .tag("status", "error")
                    .register(meterRegistry));
            
            throw new RuntimeException("Model scoring failed", e.getCause());
        }
    }

    /**
     * Get pool statistics for monitoring.
     */
    public AsyncModelStep.PoolStats getPoolStats() {
        return asyncModelStep.getPoolStats();
    }
}
