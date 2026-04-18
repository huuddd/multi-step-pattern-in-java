package com.fraud.common.dto;

import com.fraud.common.domain.Decision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReviewRequest {

    @NotNull(message = "decision is required")
    private Decision decision;

    @NotBlank(message = "reviewer is required")
    private String reviewer;
}
