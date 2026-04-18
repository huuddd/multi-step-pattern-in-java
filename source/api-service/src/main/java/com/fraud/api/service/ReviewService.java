package com.fraud.api.service;

import com.fraud.api.domain.Payment;
import com.fraud.api.domain.RiskEvent;
import com.fraud.api.exception.InvalidStateTransitionException;
import com.fraud.api.exception.PaymentNotFoundException;
import com.fraud.api.repository.PaymentRepository;
import com.fraud.api.repository.RiskEventRepository;
import com.fraud.common.domain.Decision;
import com.fraud.common.domain.PaymentState;
import com.fraud.common.domain.PipelineStep;
import com.fraud.common.dto.ReviewRequest;
import com.fraud.common.dto.ReviewResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private final PaymentRepository paymentRepository;
    private final RiskEventRepository riskEventRepository;
    private final WebhookService webhookService;

    /**
     * Submit human review decision.
     * Only allowed for payments in REVIEW state.
     */
    @Transactional
    public ReviewResponse submitDecision(String paymentId, ReviewRequest request) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        
        // Validate state
        if (payment.getState() != PaymentState.REVIEW) {
            throw new InvalidStateTransitionException(payment.getState(), 
                    request.getDecision() == Decision.ALLOW ? PaymentState.DECIDED : PaymentState.BLOCKED);
        }
        
        // Only ALLOW or BLOCK allowed from human review (not REVIEW again)
        if (request.getDecision() == Decision.REVIEW) {
            throw new IllegalArgumentException("Human review must decide ALLOW or BLOCK");
        }
        
        // Update payment
        PaymentState targetState = request.getDecision() == Decision.ALLOW 
                ? PaymentState.DECIDED 
                : PaymentState.BLOCKED;
        
        payment.transitionTo(targetState);
        payment.setDecision(request.getDecision());
        payment = paymentRepository.save(payment);
        
        // Record review event
        RiskEvent event = RiskEvent.done(paymentId, PipelineStep.DECISION, Map.of(
                "reviewer", request.getReviewer(),
                "decision", request.getDecision().name(),
                "type", "HUMAN_REVIEW"
        ));
        riskEventRepository.save(event);
        
        // Send webhook
        webhookService.sendDecisionWebhook(payment);
        
        log.info("Human review completed: payment={}, decision={}, reviewer={}", 
                paymentId, request.getDecision(), request.getReviewer());
        
        return ReviewResponse.builder()
                .paymentId(payment.getPaymentId())
                .decision(payment.getDecision())
                .state(payment.getState())
                .reviewedBy(request.getReviewer())
                .reviewedAt(Instant.now())
                .build();
    }
}
