package com.fraud.api.exception;

import com.fraud.common.domain.PaymentState;

public class InvalidStateTransitionException extends RuntimeException {
    
    public InvalidStateTransitionException(PaymentState from, PaymentState to) {
        super(String.format("Cannot transition from %s to %s", from, to));
    }
}
