package com.fraud.api.repository;

import com.fraud.api.domain.Payment;
import com.fraud.common.domain.PaymentState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, String> {

    /**
     * Find existing payment by merchant and idempotency key.
     * Used for idempotency check before processing.
     */
    Optional<Payment> findByMerchantIdAndIdempotencyKey(String merchantId, String idempotencyKey);

    /**
     * Find payments by state.
     */
    List<Payment> findByState(PaymentState state);

    /**
     * Find payments pending review.
     */
    @Query("SELECT p FROM Payment p WHERE p.state = 'REVIEW' ORDER BY p.createdAt ASC")
    List<Payment> findPendingReviews();

    /**
     * Count decisions by type for metrics.
     */
    @Query("SELECT p.decision, COUNT(p) FROM Payment p WHERE p.decision IS NOT NULL GROUP BY p.decision")
    List<Object[]> countByDecision();

    /**
     * Check if payment exists with same idempotency key but different payment_id.
     * This indicates a conflict.
     */
    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END " +
           "FROM Payment p WHERE p.merchantId = :merchantId " +
           "AND p.idempotencyKey = :idempotencyKey " +
           "AND p.paymentId != :paymentId")
    boolean existsConflict(
        @Param("merchantId") String merchantId,
        @Param("idempotencyKey") String idempotencyKey,
        @Param("paymentId") String paymentId
    );
}
