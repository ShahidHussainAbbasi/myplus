package com.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Sanitizes request parameters application-wide on the monolith (defense-in-depth on top of the
 * front-end output encoding). Auto-registered by Spring Boot as a {@code @Component} filter; runs
 * before Spring Security so the wrapped request reaches every downstream filter and controller.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class XssSanitizingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        filterChain.doFilter(new XssRequestWrapper(request), response);
    }
}
