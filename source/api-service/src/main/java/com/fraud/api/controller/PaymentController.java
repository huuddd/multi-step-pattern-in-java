package com.fraud.api.controller;

import com.fraud.api.service.PaymentService;
import com.fraud.common.dto.AuthorizeRequest;
import com.fraud.common.dto.AuthorizeResponse;
import com.fraud.common.dto.PaymentStatusResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/authorize")
    public ResponseEntity<AuthorizeResponse> authorize(@Valid @RequestBody AuthorizeRequest request) {
        try {
            MDC.put("payment_id", request.getPaymentId());
            MDC.put("merchant_id", request.getMerchantId());
            
            log.info("Received authorize request");
            AuthorizeResponse response = paymentService.authorize(request);
            log.info("Authorize completed: decision={}", response.getDecision());
            
            return ResponseEntity.ok(response);
        } finally {
            MDC.clear();
        }
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<PaymentStatusResponse> getStatus(@PathVariable("id") String paymentId) {
        try {
            MDC.put("payment_id", paymentId);
            
            log.debug("Getting payment status");
            PaymentStatusResponse response = paymentService.getStatus(paymentId);
            
            return ResponseEntity.ok(response);
        } finally {
            MDC.clear();
        }
    }
}
