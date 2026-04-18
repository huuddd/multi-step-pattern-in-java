package com.fraud.api.pipeline.steps;

import com.fraud.api.pipeline.PipelineContext;
import com.fraud.api.pipeline.PipelineStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * INGEST step: Validate and normalize the payment data.
 */
@Component
@Slf4j
public class IngestStep implements PipelineStep {

    @Override
    public PipelineContext execute(PipelineContext context) {
        log.debug("Executing INGEST step for payment: {}", context.getPayment().getPaymentId());
        
        // Normalize currency to uppercase
        String currency = context.getPayment().getCurrency();
        if (currency != null) {
            context.getPayment().setCurrency(currency.toUpperCase());
        }
        
        // Mask card_bin for logging (keep first 6 digits)
        String cardBin = context.getPayment().getCardBin();
        if (cardBin != null && cardBin.length() > 6) {
            context.getPayment().setCardBin(cardBin.substring(0, 6));
        }
        
        log.debug("INGEST step completed");
        return context;
    }

    @Override
    public String getStepName() {
        return "INGEST";
    }
}
