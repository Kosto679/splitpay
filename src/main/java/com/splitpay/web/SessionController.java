package com.splitpay.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.splitpay.SplitpayProperties;
import com.splitpay.service.AttemptLimiter;
import com.splitpay.web.Api.LoginIn;
import com.splitpay.web.Api.SessionOut;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api")
public class SessionController {

    private static final int MAX_LOGIN_FAILURES = 10;

    private final SplitpayProperties properties;
    private final AdminAuth adminAuth;
    private final AttemptLimiter limiter;

    public SessionController(SplitpayProperties properties, AdminAuth adminAuth, AttemptLimiter limiter) {
        this.properties = properties;
        this.adminAuth = adminAuth;
        this.limiter = limiter;
    }

    @GetMapping("/health")
    Map<String, Boolean> health() {
        return Map.of("ok", true);
    }

    @GetMapping("/session")
    SessionOut session(HttpServletRequest request) {
        return new SessionOut(adminAuth.isAdmin(request), properties.authRequired(),
                !properties.ingestToken().isEmpty(), properties.publicUrl());
    }

    @PostMapping("/login")
    Map<String, Boolean> login(@RequestBody LoginIn in, HttpServletRequest request) {
        String key = "login:" + request.getRemoteAddr();
        limiter.check(key, MAX_LOGIN_FAILURES);
        if (properties.authRequired() && !constantTimeEquals(in.password(), properties.password())) {
            limiter.fail(key);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong password");
        }
        limiter.reset(key);
        adminAuth.signIn(request);
        return Map.of("ok", true);
    }

    @PostMapping("/logout")
    Map<String, Boolean> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Map.of("ok", true);
    }

    static boolean constantTimeEquals(String a, String b) {
        byte[] left = (a == null ? "" : a).getBytes(StandardCharsets.UTF_8);
        byte[] right = (b == null ? "" : b).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}
