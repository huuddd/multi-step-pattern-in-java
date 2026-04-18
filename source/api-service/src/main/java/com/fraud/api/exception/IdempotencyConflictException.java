package com.fraud.api.exception;

public class IdempotencyConflictException extends RuntimeException {
    
    public IdempotencyConflictException(String idempotencyKey) {
        super("Request with same idempotency_key but different payload: " + idempotencyKey);
    }
}
