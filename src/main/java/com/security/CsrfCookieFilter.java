package com.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring Security 6 loads the CSRF token lazily (deferred), so {@code CookieCsrfTokenRepository}
 * doesn't write the {@code XSRF-TOKEN} cookie until something actually reads the token. This filter
 * reads it on every request, forcing the cookie to be materialized so the dashboard JS
 * ({@code $.ajaxSetup}) can echo it back as the {@code X-XSRF-TOKEN} header.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
        if (csrfToken != null) {
            csrfToken.getToken(); // triggers the deferred token load + Set-Cookie
        }
        filterChain.doFilter(request, response);
    }
}
