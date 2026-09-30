package com.splitpay.domain;

public enum PaymentStatus {
    MATCHED,
    UNMATCHED,
    IGNORED;

    public String json() {
        return name().toLowerCase();
    }
}
