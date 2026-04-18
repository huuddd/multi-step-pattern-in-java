package com.fraud.api.service;

import com.fraud.api.config.MetricsConfig.InFlightRequestsTracker;
import com.fraud.api.domain.Payment;
import com.fraud.api.exception.IdempotencyConflictException;
import com.fraud.api.exception.PaymentNotFoundException;
import com.fraud.api.pipeline.FraudDetectionPipeline;
import com.fraud.api.pipeline.PipelineContext;
import com.fraud.api.repository.PaymentRepository;
import com.fraud.api.repository.RiskEventRepository;
import com.fraud.common.domain.Decision;
import com.fraud.common.domain.PipelineStep;
import com.fraud.common.dto.AuthorizeRequest;
import com.fraud.common.dto.AuthorizeResponse;
import com.fraud.common.dto.PaymentStatusResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final RiskEventRepository riskEventRepository;
    private final FraudDetectionPipeline pipeline;
    private final WebhookService webhookService;
    private final InFlightRequestsTracker inFlightTracker;
    private final Counter authorizeCounter;
    private final Counter decisionAllowCounter;
    private final Counter decisionReviewCounter;
    private final Counter decisionBlockCounter;

    public PaymentService(
            PaymentRepository paymentRepository,
            RiskEventRepository riskEventRepository,
            FraudDetectionPipeline pipeline,
            WebhookService webhookService,
            InFlightRequestsTracker inFlightTracker,
            MeterRegistry meterRegistry) {
        this.paymentRepository = paymentRepository;
        this.riskEventRepository = riskEventRepository;
        this.pipeline = pipeline;
        this.webhookService = webhookService;
        this.inFlightTracker = inFlightTracker;
        this.authorizeCounter = Counter.builder("authorize_requests_total")
                .description("Total authorize requests")
                .register(meterRegistry);
        this.decisionAllowCounter = Counter.builder("decision_total")
                .tag("type", "allow")
                .register(meterRegistry);
        this.decisionReviewCounter = Counter.builder("decision_total")
                .tag("type", "review")
                .register(meterRegistry);
        this.decisionBlockCounter = Counter.builder("decision_total")
                .tag("type", "block")
                .register(meterRegistry);
    }

    /**
     * Authorize a payment through the fraud detection pipeline.
     * Idempotent: same idempotency_key returns cached result.
     */
    @Transactional
    public AuthorizeResponse authorize(AuthorizeRequest request) {
        authorizeCounter.increment();
        inFlightTracker.increment();
        
        try {
            // Check idempotency - return existing result if already processed
            Optional<Payment> existing = paymentRepository.findByMerchantIdAndIdempotencyKey(
                    request.getMerchantId(), request.getIdempotencyKey());
            
            if (existing.isPresent()) {
                Payment payment = existing.get();
                
                // Check for conflict (same key but different payment_id)
                if (!payment.getPaymentId().equals(request.getPaymentId())) {
                    throw new IdempotencyConflictException(request.getIdempotencyKey());
                }
                
                log.info("Returning cached result for idempotency_key: {}", request.getIdempotencyKey());
                return buildResponse(payment);
            }

            // Create new payment
            Payment payment = createPayment(request);
            payment = paymentRepository.save(payment);
            
            // Execute fraud detection pipeline
            PipelineContext result = pipeline.execute(payment);
            
            // Update payment with decision
            payment.complete(result.getDecision(), result.getRiskScore());
            payment = paymentRepository.save(payment);
            
            // Record decision metric
            recordDecisionMetric(result.getDecision());
            
            // Send webhook for non-REVIEW decisions
            if (result.getDecision() != Decision.REVIEW) {
                webhookService.sendDecisionWebhook(payment);
            }
            
            return buildResponse(payment);
        } finally {
            inFlightTracker.decrement();
        }
    }
    
    private void recordDecisionMetric(Decision decision) {
        switch (decision) {
            case ALLOW -> decisionAllowCounter.increment();
            case REVIEW -> decisionReviewCounter.increment();
            case BLOCK -> decisionBlockCounter.increment();
        }
    }

    /**
     * Get payment status including pipeline progress.
     */
    @Transactional(readOnly = true)
    public PaymentStatusResponse getStatus(String paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        
        // Get pipeline status
        Map<String, String> pipelineStatus = getPipelineStatus(paymentId);
        
        return PaymentStatusResponse.builder()
                .paymentId(payment.getPaymentId())
                .state(payment.getState())
                .decision(payment.getDecision())
                .riskScore(payment.getRiskScore())
                .pipelineStatus(pipelineStatus)
                .build();
    }

    private Payment createPayment(AuthorizeRequest request) {
        Payment payment = new Payment();
        payment.setPaymentId(request.getPaymentId());
        payment.setMerchantId(request.getMerchantId());
        payment.setAmount(request.getAmount());
        payment.setCurrency(request.getCurrency());
        payment.setCardBin(request.getCardBin());
        payment.setIp(maskIp(request.getIp()));
        payment.setDeviceId(request.getDeviceId());
        payment.setIdempotencyKey(request.getIdempotencyKey());
        return payment;
    }

    private AuthorizeResponse buildResponse(Payment payment) {
        return AuthorizeResponse.builder()
                .paymentId(payment.getPaymentId())
                .decision(payment.getDecision())
                .riskScore(payment.getRiskScore())
                .state(payment.getState())
                .build();
    }

    private Map<String, String> getPipelineStatus(String paymentId) {
        Map<String, String> status = new HashMap<>();
        
        // Initialize all steps as PENDING
        for (PipelineStep step : PipelineStep.values()) {
            status.put(step.name(), "PENDING");
        }
        
        // Update with actual status from risk_events
        List<Object[]> events = riskEventRepository.findLatestStatusByPaymentId(paymentId);
        for (Object[] row : events) {
            PipelineStep step = (PipelineStep) row[0];
            String stepStatus = row[1].toString();
            status.put(step.name(), stepStatus);
        }
        
        return status;
    }

    /**
     * Mask IP address for privacy (x.x.x.*)
     */
    private String maskIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return ip;
        }
        int lastDot = ip.lastIndexOf('.');
        if (lastDot > 0) {
            return ip.substring(0, lastDot) + ".*";
        }
        return ip;
    }
}
