package com.fraud.api.exception;

public class PaymentNotFoundException extends RuntimeException {
    
    public PaymentNotFoundException(String paymentId) {
        super("Payment not found: " + paymentId);
    }
}
