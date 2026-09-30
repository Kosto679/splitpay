package com.splitpay.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentDraft(
        BigDecimal amount,
        String currency,
        LocalDateTime paidAt,
        String source,
        String rawText,
        String payerHint) {

    public PaymentDraft withAmount(BigDecimal newAmount) {
        return new PaymentDraft(newAmount, currency, paidAt, source, rawText, payerHint);
    }
}
