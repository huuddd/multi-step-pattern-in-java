package com.fraud.api.pipeline.steps;

import com.fraud.api.pipeline.PipelineContext;
import com.fraud.api.pipeline.PipelineStep;
import com.fraud.common.domain.Decision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * DECISION step: Combine model score and rules to make final decision.
 */
@Component
@Slf4j
public class DecisionStep implements PipelineStep {

    private static final BigDecimal BLOCK_THRESHOLD = new BigDecimal("0.7");
    private static final BigDecimal REVIEW_THRESHOLD = new BigDecimal("0.4");

    @Override
    public PipelineContext execute(PipelineContext context) {
        log.debug("Executing DECISION step for payment: {}", context.getPayment().getPaymentId());
        
        // Check if any rule failed (hard block)
        Map<String, Boolean> ruleResults = context.getRuleResults();
        boolean anyRuleFailed = ruleResults.values().stream().anyMatch(passed -> !passed);
        
        if (anyRuleFailed) {
            context.setDecision(Decision.BLOCK);
            log.info("Decision: BLOCK (rule failed)");
            return context;
        }
        
        // Use risk score for soft decision
        BigDecimal riskScore = context.getRiskScore();
        if (riskScore == null) {
            riskScore = BigDecimal.ZERO;
        }
        
        Decision decision;
        if (riskScore.compareTo(BLOCK_THRESHOLD) >= 0) {
            decision = Decision.BLOCK;
        } else if (riskScore.compareTo(REVIEW_THRESHOLD) >= 0) {
            decision = Decision.REVIEW;
        } else {
            decision = Decision.ALLOW;
        }
        
        context.setDecision(decision);
        log.info("Decision: {} (risk_score={})", decision, riskScore);
        
        return context;
    }

    @Override
    public String getStepName() {
        return "DECISION";
    }
}
