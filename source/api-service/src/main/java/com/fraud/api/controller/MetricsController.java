package com.fraud.api.controller;

import com.fraud.api.service.MetricsService;
import com.fraud.common.dto.MetricsStatsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsService metricsService;

    @GetMapping("/stats")
    public ResponseEntity<MetricsStatsResponse> getStats() {
        return ResponseEntity.ok(metricsService.getStats());
    }
}
