package com.inboxintelligence.processor.exception;

public class RetryableAIException extends RuntimeException {

    public RetryableAIException(String message, Throwable cause) {
        super(message, cause);
    }
}
