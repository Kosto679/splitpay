package com.splitpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.splitpay.service.NotificationParser.ParsedNotice;

class NotificationParserTest {

    private final NotificationParser parser = new NotificationParser();

    @Test
    void parsesEuroAmountAndSender() {
        ParsedNotice notice = parser.parse("You received 12,50 EUR from Maria Papa");

        assertThat(notice.amount()).isEqualByComparingTo("12.50");
        assertThat(notice.currency()).isEqualTo("EUR");
        assertThat(notice.payerHint()).isEqualTo("Maria Papa");
    }

    @Test
    void parsesGreekIrisNotification() {
        ParsedNotice notice = parser.parse("Πίστωση 15,00€ από ΚΩΣΤΟΠΟΥΛΟΣ ΝΙΚΟΣ στις 18/09");

        assertThat(notice.amount()).isEqualByComparingTo("15.00");
        assertThat(notice.payerHint()).isEqualTo("ΚΩΣΤΟΠΟΥΛΟΣ ΝΙΚΟΣ");
    }

    @Test
    void parsesSenderBeforeVerb() {
        ParsedNotice notice = parser.parse("Nikos Kostopoulos sent you €8.00");

        assertThat(notice.amount()).isEqualByComparingTo("8.00");
        assertThat(notice.payerHint()).isEqualTo("Nikos Kostopoulos");
    }

    @Test
    void handlesThousandsSeparators() {
        assertThat(NotificationParser.parseAmount("1.234,56")).isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(NotificationParser.parseAmount("1,234.56")).isEqualByComparingTo(new BigDecimal("1234.56"));
    }

    @Test
    void normalizesGreekAccentsAndFinalSigma() {
        assertThat(NotificationParser.normalizeName("ΚΏΣΤΑΣ Παπάς")).isEqualTo(NotificationParser.normalizeName("κωστασ παπασ"));
    }
}
