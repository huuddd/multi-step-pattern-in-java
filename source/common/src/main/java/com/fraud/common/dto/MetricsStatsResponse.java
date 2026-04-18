package com.fraud.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class MetricsStatsResponse {

    private ThroughputStats throughput;

    private LatencyStats latency;

    @JsonProperty("queue_depth")
    private Map<String, Long> queueDepth;

    private Map<String, Long> decisions;

    @Data
    @Builder
    public static class ThroughputStats {
        @JsonProperty("rps_1m")
        private double rps1m;

        @JsonProperty("rps_5m")
        private double rps5m;
    }

    @Data
    @Builder
    public static class LatencyStats {
        @JsonProperty("p50_ms")
        private double p50Ms;

        @JsonProperty("p95_ms")
        private double p95Ms;

        @JsonProperty("p99_ms")
        private double p99Ms;
    }
}
