package com.tibudget.test;

import java.io.IOException;

/**
 * Describe an offline replay mismatch without exposing URLs, headers or request data.
 * The request number refers to the numbered recording in the scenario directory.
 */
public final class ReplayMismatchException extends IOException {
    private static final long serialVersionUID = 1L;
    private final int requestNumber;
    private final String reason;

    /**
     * Use a fixed reason such as RECORDING_MISSING, METHOD_OR_URL or BODY.
     */
    public ReplayMismatchException(int requestNumber, String reason) {
        super("Scenario mismatch at request " + requestNumber + ": " + reason);
        this.requestNumber = requestNumber;
        this.reason = reason;
    }

    public int getRequestNumber() {
        return requestNumber;
    }

    public String getReason() {
        return reason;
    }
}
