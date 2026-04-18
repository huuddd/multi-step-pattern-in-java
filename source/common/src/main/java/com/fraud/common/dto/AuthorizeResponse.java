package com.fraud.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fraud.common.domain.Decision;
import com.fraud.common.domain.PaymentState;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class AuthorizeResponse {

    @JsonProperty("payment_id")
    private String paymentId;

    private Decision decision;

    @JsonProperty("risk_score")
    private BigDecimal riskScore;

    private PaymentState state;
}
