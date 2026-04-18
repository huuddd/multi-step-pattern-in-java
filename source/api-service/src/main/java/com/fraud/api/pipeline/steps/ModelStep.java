package com.fraud.api.pipeline.steps;

import com.fraud.api.pipeline.PipelineContext;
import com.fraud.api.pipeline.PipelineStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * MODEL step: Calculate risk score using ML model or heuristics.
 * In production, this would call an ML inference service.
 */
@Component
@Slf4j
public class ModelStep implements PipelineStep {

    @Override
    public PipelineContext execute(PipelineContext context) {
        log.debug("Executing MODEL step for payment: {}", context.getPayment().getPaymentId());
        
        // Simulate model scoring using heuristics
        double score = calculateRiskScore(context.getFeatures(), context.getPayment().getAmount());
        
        BigDecimal riskScore = BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
        context.setRiskScore(riskScore);
        
        log.debug("MODEL step completed: risk_score={}", riskScore);
        return context;
    }

    private double calculateRiskScore(Map<String, Object> features, Long amount) {
        double score = 0.0;
        
        // High amount increases risk
        double amountPercentile = (double) features.getOrDefault("amount_percentile", 0.0);
        score += amountPercentile * 0.3;
        
        // High card BIN risk
        String binRisk = (String) features.getOrDefault("card_bin_risk_level", "LOW");
        if ("HIGH".equals(binRisk)) {
            score += 0.3;
        }
        
        // High transaction velocity
        int deviceTxCount = (int) features.getOrDefault("device_tx_count_24h", 0);
        if (deviceTxCount > 10) {
            score += 0.2;
        }
        
        // Random noise to simulate model uncertainty
        score += Math.random() * 0.1;
        
        return Math.min(1.0, Math.max(0.0, score));
    }

    @Override
    public String getStepName() {
        return "MODEL";
    }
}
