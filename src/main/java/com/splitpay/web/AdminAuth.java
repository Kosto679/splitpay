package com.splitpay.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import com.splitpay.SplitpayProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/** Guards the admin API with the session created by {@code /api/login}. */
@Component
public class AdminAuth implements HandlerInterceptor {

    private static final String SESSION_KEY = "splitpay.admin";

    private final SplitpayProperties properties;

    public AdminAuth(SplitpayProperties properties) {
        this.properties = properties;
    }

    public boolean isAdmin(HttpServletRequest request) {
        if (!properties.authRequired()) {
            return true;
        }
        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_KEY));
    }

    public void signIn(HttpServletRequest request) {
        request.getSession(true);
        request.changeSessionId();
        request.getSession().setAttribute(SESSION_KEY, Boolean.TRUE);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (isAdmin(request)) {
            return true;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
    }
}
