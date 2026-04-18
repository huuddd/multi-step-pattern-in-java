package com.fraud.api.controller;

import com.fraud.api.service.ReviewService;
import com.fraud.common.dto.ReviewRequest;
import com.fraud.common.dto.ReviewResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/reviews")
@RequiredArgsConstructor
@Slf4j
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping("/{paymentId}/decision")
    public ResponseEntity<ReviewResponse> submitDecision(
            @PathVariable String paymentId,
            @Valid @RequestBody ReviewRequest request) {
        try {
            MDC.put("payment_id", paymentId);
            MDC.put("reviewer", request.getReviewer());
            
            log.info("Received review decision: {}", request.getDecision());
            ReviewResponse response = reviewService.submitDecision(paymentId, request);
            log.info("Review completed: decision={}", response.getDecision());
            
            return ResponseEntity.ok(response);
        } finally {
            MDC.clear();
        }
    }
}
