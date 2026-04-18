package com.fraud.api.repository;

import com.fraud.api.domain.RiskEvent;
import com.fraud.common.domain.PipelineStep;
import com.fraud.common.domain.StepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RiskEventRepository extends JpaRepository<RiskEvent, Long> {

    /**
     * Find all events for a payment.
     */
    List<RiskEvent> findByPaymentIdOrderByCreatedAtAsc(String paymentId);

    /**
     * Check if step is already done for idempotency.
     */
    boolean existsByPaymentIdAndStepAndStatus(String paymentId, PipelineStep step, StepStatus status);

    /**
     * Find latest event for a payment and step.
     */
    Optional<RiskEvent> findFirstByPaymentIdAndStepOrderByCreatedAtDesc(String paymentId, PipelineStep step);

    /**
     * Get pipeline status for a payment.
     */
    @Query("SELECT e.step, e.status FROM RiskEvent e " +
           "WHERE e.paymentId = :paymentId " +
           "AND e.createdAt = (SELECT MAX(e2.createdAt) FROM RiskEvent e2 " +
           "WHERE e2.paymentId = e.paymentId AND e2.step = e.step)")
    List<Object[]> findLatestStatusByPaymentId(@Param("paymentId") String paymentId);

    /**
     * Count events by step and status for metrics.
     */
    @Query("SELECT e.step, e.status, COUNT(e) FROM RiskEvent e GROUP BY e.step, e.status")
    List<Object[]> countByStepAndStatus();
}
