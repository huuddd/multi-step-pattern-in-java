package com.fraud.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fraud.common.domain.Decision;
import com.fraud.common.domain.PaymentState;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class PaymentStatusResponse {

    @JsonProperty("payment_id")
    private String paymentId;

    private PaymentState state;

    private Decision decision;

    @JsonProperty("risk_score")
    private BigDecimal riskScore;

    @JsonProperty("pipeline_status")
    private Map<String, String> pipelineStatus;
}
