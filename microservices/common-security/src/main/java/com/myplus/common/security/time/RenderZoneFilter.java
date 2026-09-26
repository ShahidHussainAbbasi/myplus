package com.myplus.common.security.time;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * TZ-1 — reads {@code X-Render-Tz} into {@link RenderZone} for the life of ONE request, and always clears it.
 * A request without the header (every service-to-service call) renders in UTC, exactly as before.
 */
public class RenderZoneFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RenderZone.parse(request.getHeader(RenderZone.HEADER)).ifPresent(RenderZone::set);
        try {
            chain.doFilter(request, response);
        } finally {
            RenderZone.clear();   // pooled threads must never carry one request's zone into the next
        }
    }
}
