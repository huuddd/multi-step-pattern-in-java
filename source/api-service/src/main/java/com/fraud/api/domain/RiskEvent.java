package com.fraud.api.domain;

import com.fraud.common.domain.PipelineStep;
import com.fraud.common.domain.StepStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "risk_events")
@Getter
@Setter
@NoArgsConstructor
public class RiskEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PipelineStep step;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> detail;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public static RiskEvent running(String paymentId, PipelineStep step) {
        RiskEvent event = new RiskEvent();
        event.setPaymentId(paymentId);
        event.setStep(step);
        event.setStatus(StepStatus.RUNNING);
        return event;
    }

    public static RiskEvent done(String paymentId, PipelineStep step, Map<String, Object> detail) {
        RiskEvent event = new RiskEvent();
        event.setPaymentId(paymentId);
        event.setStep(step);
        event.setStatus(StepStatus.DONE);
        event.setDetail(detail);
        return event;
    }

    public static RiskEvent failed(String paymentId, PipelineStep step, String error) {
        RiskEvent event = new RiskEvent();
        event.setPaymentId(paymentId);
        event.setStep(step);
        event.setStatus(StepStatus.FAILED);
        event.setDetail(Map.of("error", error));
        return event;
    }
}
