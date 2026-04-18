package com.fraud.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fraud.common.domain.Decision;
import com.fraud.common.domain.PaymentState;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ReviewResponse {

    @JsonProperty("payment_id")
    private String paymentId;

    private Decision decision;

    private PaymentState state;

    @JsonProperty("reviewed_by")
    private String reviewedBy;

    @JsonProperty("reviewed_at")
    private Instant reviewedAt;
}
