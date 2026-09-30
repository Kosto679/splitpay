package com.splitpay.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class NotificationParser {

    public record ParsedNotice(BigDecimal amount, String currency, String payerHint) {
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String CURRENCY = "(eur|euros?|usd|gbp|€|\\$|£)";
    private static final String NUMBER = "(\\d{1,3}(?:[.,\\s]\\d{3})*[.,]\\d{2}|\\d+)";
    private static final Pattern AMOUNT = Pattern.compile(
            CURRENCY + "\\s*" + NUMBER + "|" + NUMBER + "\\s*" + CURRENCY, FLAGS);

    private static final String NAME = "(\\p{L}[\\p{L}.'’ -]{1,80})";
    private static final Pattern PAYER_AFTER = Pattern.compile(
            "(?<!\\p{L})(?:from|sender|payer|από|απο|παρά)\\s*[:\\-]?\\s*" + NAME, FLAGS);
    private static final Pattern PAYER_BEFORE = Pattern.compile(
            "^\\s*" + NAME + "\\s+(?:sent|paid|transferred)\\s+you", FLAGS);
    private static final Pattern NAME_STOP = Pattern.compile(
            "\\s+(?:on|at|via|with|to|for|στις|την|το|στο|μέσω)(?:\\s.*)?$", FLAGS);

    public ParsedNotice parse(String text) {
        String original = text == null ? "" : text.strip();
        BigDecimal amount = null;
        String currency = "EUR";
        Matcher matcher = AMOUNT.matcher(original);
        while (matcher.find()) {
            String token = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            BigDecimal parsed = parseAmount(token);
            if (parsed == null) {
                continue;
            }
            amount = parsed;
            String marker = matcher.group(1) != null ? matcher.group(1) : matcher.group(4);
            currency = currencyOf(marker);
            break;
        }
        return new ParsedNotice(amount, currency, payerOf(original));
    }

    public static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        String plain = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        plain = plain.toLowerCase(Locale.ROOT).replace('ς', 'σ');
        return plain.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    static BigDecimal parseAmount(String token) {
        String compact = token.replaceAll("[\\s\\u00a0]", "");
        int comma = compact.lastIndexOf(',');
        int dot = compact.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            compact = comma > dot
                    ? compact.replace(".", "").replace(',', '.')
                    : compact.replace(",", "");
        } else if (comma >= 0) {
            boolean decimalComma = compact.length() - comma - 1 == 2;
            compact = decimalComma ? compact.replace(',', '.') : compact.replace(",", "");
        }
        try {
            BigDecimal value = new BigDecimal(compact).setScale(2, RoundingMode.HALF_UP);
            if (value.signum() <= 0 || value.compareTo(new BigDecimal("1000000")) > 0) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String currencyOf(String marker) {
        String m = marker == null ? "" : marker.toUpperCase(Locale.ROOT);
        if (m.equals("$") || m.equals("USD")) {
            return "USD";
        }
        if (m.equals("£") || m.equals("GBP")) {
            return "GBP";
        }
        return "EUR";
    }

    private static String payerOf(String text) {
        for (Pattern pattern : new Pattern[] {PAYER_AFTER, PAYER_BEFORE}) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String name = NAME_STOP.matcher(matcher.group(1).strip()).replaceFirst("");
                name = name.replaceAll("[\\s.\\-]+$", "").strip();
                if (!name.isEmpty()) {
                    return name;
                }
            }
        }
        return "";
    }
}
