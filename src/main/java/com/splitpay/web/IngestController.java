package com.splitpay.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.splitpay.SplitpayProperties;
import com.splitpay.domain.Payment;
import com.splitpay.domain.PaymentStatus;
import com.splitpay.service.LedgerService;
import com.splitpay.web.Api.IngestIn;
import com.splitpay.web.Api.RecordOut;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** Receives bank notifications from phone automations and the installed web app's share sheet. */
@RestController
public class IngestController {

    private final SplitpayProperties properties;
    private final AdminAuth adminAuth;
    private final LedgerService ledger;
    private final ViewMapper views;

    public IngestController(SplitpayProperties properties, AdminAuth adminAuth, LedgerService ledger,
            ViewMapper views) {
        this.properties = properties;
        this.adminAuth = adminAuth;
        this.ledger = ledger;
        this.views = views;
    }

    @PostMapping("/api/ingest")
    RecordOut ingest(@Valid @RequestBody IngestIn in, HttpServletRequest request) {
        requireToken(request);
        String source = in.source() == null || in.source().isBlank() ? "ingest" : in.source();
        return views.record(ledger.ingest(in.text().strip(), source, ledger.resolvePaidAt(in.paidAt())));
    }

    @PostMapping("/api/ingest/form")
    ResponseEntity<?> ingestForm(
            @RequestParam(defaultValue = "") String title,
            @RequestParam(defaultValue = "") String text,
            @RequestParam(defaultValue = "share") String source,
            @RequestHeader(value = "Accept", defaultValue = "") String accept,
            HttpServletRequest request) {
        if (!tokenFrom(request).isEmpty()) {
            requireToken(request);
        } else if (!adminAuth.isAdmin(request)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
        }
        String body = Stream.of(title, text).map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.joining("\n"));
        if (body.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to ingest");
        }
        List<Payment> saved = ledger.ingest(body, source, ledger.now());
        if (!accept.contains("application/json")) {
            boolean unmatched = saved.stream().anyMatch(p -> p.getStatus() == PaymentStatus.UNMATCHED);
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(unmatched ? "/#/inbox" : "/#/")).build();
        }
        return ResponseEntity.ok(views.record(saved));
    }

    private void requireToken(HttpServletRequest request) {
        String expected = properties.ingestToken();
        if (expected.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INGEST_TOKEN is not configured");
        }
        byte[] given = tokenFrom(request).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid ingest token");
        }
    }

    private static String tokenFrom(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).strip();
        }
        String custom = request.getHeader("X-Ingest-Token");
        if (custom != null && !custom.isBlank()) {
            return custom.strip();
        }
        String query = request.getParameter("token");
        return query == null ? "" : query.strip();
    }
}
