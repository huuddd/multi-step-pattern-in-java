package com.fraud.api.pipeline.steps;

import com.fraud.api.pipeline.PipelineContext;
import com.fraud.api.pipeline.PipelineStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

/**
 * RULE step: Apply business rules, blacklists, geofence checks.
 */
@Component
@Slf4j
public class RuleStep implements PipelineStep {

    // Simulated blacklists
    private static final Set<String> BLOCKED_CARD_BINS = Set.of("000000", "111111");
    private static final Set<String> BLOCKED_DEVICE_IDS = Set.of("blocked-device-1", "blocked-device-2");
    private static final Set<String> HIGH_RISK_COUNTRIES = Set.of("XX", "YY");

    @Override
    public PipelineContext execute(PipelineContext context) {
        log.debug("Executing RULE step for payment: {}", context.getPayment().getPaymentId());
        
        // Rule 1: Blacklisted card BIN
        boolean cardBinOk = !BLOCKED_CARD_BINS.contains(context.getPayment().getCardBin());
        context.addRuleResult("card_bin_blacklist", cardBinOk);
        
        // Rule 2: Blacklisted device
        boolean deviceOk = !BLOCKED_DEVICE_IDS.contains(context.getPayment().getDeviceId());
        context.addRuleResult("device_blacklist", deviceOk);
        
        // Rule 3: High-risk country
        String ipCountry = (String) context.getFeatures().getOrDefault("ip_country", "");
        boolean countryOk = !HIGH_RISK_COUNTRIES.contains(ipCountry);
        context.addRuleResult("country_risk", countryOk);
        
        // Rule 4: Amount threshold
        boolean amountOk = context.getPayment().getAmount() <= 50_000_000; // 50M VND
        context.addRuleResult("amount_threshold", amountOk);
        
        // Rule 5: Risk score threshold
        BigDecimal riskScore = context.getRiskScore();
        boolean riskOk = riskScore == null || riskScore.doubleValue() < 0.8;
        context.addRuleResult("risk_score_threshold", riskOk);
        
        log.debug("RULE step completed: {} rules evaluated", context.getRuleResults().size());
        return context;
    }

    @Override
    public String getStepName() {
        return "RULE";
    }
}
