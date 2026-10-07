package com.github.lechandonga.firmwarestagedrolloutjava.exception;

public class RolloutException extends RuntimeException {

    private final RejectReason reason;

    public RolloutException(RejectReason reason) {
        super(reason.getDescription());
        this.reason = reason;
    }

    public RolloutException(RejectReason reason, String detail) {
        super(reason.getDescription() + ": " + detail);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
