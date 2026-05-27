package com.inboxintelligence.processor.exception;


public class RetryableOllamaException extends RuntimeException {

    public RetryableOllamaException(String message, Throwable cause) {
        super(message, cause);
    }
}
