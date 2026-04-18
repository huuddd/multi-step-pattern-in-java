package com.fraud.api.pipeline;

import com.fraud.api.domain.Payment;
import com.fraud.common.domain.Decision;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Context object passed through the pipeline.
 * Contains payment data and accumulated results from each step.
 */
@Data
@Builder
public class PipelineContext {
    
    private Payment payment;
    
    @Builder.Default
    private Map<String, Object> features = new HashMap<>();
    
    private BigDecimal riskScore;
    
    private Decision decision;
    
    @Builder.Default
    private Map<String, Boolean> ruleResults = new HashMap<>();
    
    public void addFeature(String key, Object value) {
        features.put(key, value);
    }
    
    public void addRuleResult(String ruleName, boolean passed) {
        ruleResults.put(ruleName, passed);
    }
}
