package com.github.lechandonga.firmwarestagedrolloutjava.exception;

public class BusinessException extends RuntimeException {
    private final RejectReason reason;

    public BusinessException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
