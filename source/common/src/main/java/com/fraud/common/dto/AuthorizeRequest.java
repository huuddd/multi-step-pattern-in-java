package com.fraud.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class AuthorizeRequest {

    @NotBlank(message = "payment_id is required")
    @JsonProperty("payment_id")
    private String paymentId;

    @NotBlank(message = "merchant_id is required")
    @JsonProperty("merchant_id")
    private String merchantId;

    @NotNull(message = "amount is required")
    @Positive(message = "amount must be positive")
    private Long amount;

    @NotBlank(message = "currency is required")
    private String currency;

    @JsonProperty("card_bin")
    private String cardBin;

    private String ip;

    @JsonProperty("device_id")
    private String deviceId;

    @NotBlank(message = "idempotency_key is required")
    @JsonProperty("idempotency_key")
    private String idempotencyKey;
}
