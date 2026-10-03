package com.splitpay;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "splitpay")
public record SplitpayProperties(
        String password,
        String ingestToken,
        String publicUrl,
        String timezone,
        String mailFrom,
        String supportEmail) {

    public SplitpayProperties {
        password = password == null ? "" : password;
        ingestToken = ingestToken == null ? "" : ingestToken;
        publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
        timezone = timezone == null || timezone.isBlank() ? "Europe/Athens" : timezone;
        mailFrom = mailFrom == null ? "" : mailFrom.trim();
        supportEmail = supportEmail == null ? "" : supportEmail.trim();
    }

    public boolean authRequired() {
        return !password.isEmpty();
    }
}
