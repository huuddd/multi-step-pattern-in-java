package com.fraud.api.service;

import com.fraud.api.domain.Payment;
import com.fraud.api.domain.RiskEvent;
import com.fraud.api.repository.RiskEventRepository;
import com.fraud.common.domain.PipelineStep;
import com.fraud.common.domain.StepStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Webhook service for notifying merchants of decisions.
 * Currently mocked - just logs the webhook payload.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private final RiskEventRepository riskEventRepository;

    /**
     * Send decision webhook to merchant.
     * Idempotent: checks if webhook already sent before sending.
     */
    public void sendDecisionWebhook(Payment payment) {
        String paymentId = payment.getPaymentId();
        
        // Check idempotency - skip if webhook already sent
        if (riskEventRepository.existsByPaymentIdAndStepAndStatus(
                paymentId, PipelineStep.WEBHOOK, StepStatus.DONE)) {
            log.debug("Webhook already sent for payment: {}", paymentId);
            return;
        }

        // Generate unique webhook ID for this delivery attempt
        String webhookId = "wh-" + UUID.randomUUID();
        
        // Build webhook payload
        Map<String, Object> payload = Map.of(
                "webhook_id", webhookId,
                "payment_id", payment.getPaymentId(),
                "merchant_id", payment.getMerchantId(),
                "idempotency_key", payment.getIdempotencyKey(),
                "decision", payment.getDecision().name(),
                "risk_score", payment.getRiskScore(),
                "state", payment.getState().name()
        );

        // Mock: just log the webhook
        log.info("WEBHOOK [MOCK] → merchant={}, payload={}", 
                payment.getMerchantId(), payload);

        // Record webhook sent
        RiskEvent event = RiskEvent.done(paymentId, PipelineStep.WEBHOOK, Map.of(
                "webhook_id", webhookId,
                "merchant_id", payment.getMerchantId(),
                "status", "SENT"
        ));
        riskEventRepository.save(event);
    }
}
