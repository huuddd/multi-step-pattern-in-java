package com.fraud.api.pipeline.steps;

import com.fraud.api.pipeline.PipelineContext;
import com.fraud.api.pipeline.PipelineStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * FEATURE step: Extract features for model scoring.
 * In production, this would query DB, Redis, and 3rd-party APIs.
 */
@Component
@Slf4j
public class FeatureStep implements PipelineStep {

    private final Random random = new Random();

    @Override
    public PipelineContext execute(PipelineContext context) {
        log.debug("Executing FEATURE step for payment: {}", context.getPayment().getPaymentId());
        
        // Simulate feature extraction
        // In production: query merchant history, device fingerprint, IP geolocation, etc.
        
        context.addFeature("merchant_tx_count_24h", random.nextInt(100));
        context.addFeature("device_tx_count_24h", random.nextInt(20));
        context.addFeature("ip_country", "VN");
        context.addFeature("card_bin_risk_level", random.nextDouble() < 0.1 ? "HIGH" : "LOW");
        context.addFeature("amount_percentile", calculateAmountPercentile(context.getPayment().getAmount()));
        
        log.debug("FEATURE step completed: {} features extracted", context.getFeatures().size());
        return context;
    }

    private double calculateAmountPercentile(Long amount) {
        // Simplified: assume average transaction is 500,000 VND
        double avg = 500_000.0;
        return Math.min(1.0, amount / (avg * 3));
    }

    @Override
    public String getStepName() {
        return "FEATURE";
    }
}
