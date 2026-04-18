package com.fraud.common.domain;

import java.util.Set;

/**
 * Payment state machine.
 * 
 * Valid transitions:
 * - PENDING → DECIDED (pipeline completes with ALLOW)
 * - PENDING → REVIEW (pipeline completes, needs human review)
 * - PENDING → BLOCKED (pipeline completes with BLOCK)
 * - REVIEW → DECIDED (human approves)
 * - REVIEW → BLOCKED (human rejects)
 */
public enum PaymentState {
    PENDING(Set.of("DECIDED", "REVIEW", "BLOCKED")),
    DECIDED(Set.of()),  // terminal
    REVIEW(Set.of("DECIDED", "BLOCKED")),
    BLOCKED(Set.of()); // terminal

    private final Set<String> allowedTransitions;

    PaymentState(Set<String> allowedTransitions) {
        this.allowedTransitions = allowedTransitions;
    }

    public boolean canTransitionTo(PaymentState target) {
        return allowedTransitions.contains(target.name());
    }

    public boolean isTerminal() {
        return allowedTransitions.isEmpty();
    }
}
