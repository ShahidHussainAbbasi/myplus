package com.myplus.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that wraps each request in {@link XssRequestWrapper}, so all downstream
 * parameter reads (controllers, DTO binding) see sanitized values. Defense-in-depth on top of
 * front-end output encoding. Runs early in the chain (before Spring Security / DispatcherServlet).
 */
public class XssSanitizingFilter extends OncePerRequestFilter implements Ordered {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        filterChain.doFilter(new XssRequestWrapper(request), response);
    }

    @Override
    public int getOrder() {
        // Ahead of Spring Security (FilterChainProxy defaults to -100) so the wrapped request
        // propagates to every downstream filter and the controllers.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
