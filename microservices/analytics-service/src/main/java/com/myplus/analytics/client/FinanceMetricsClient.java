package com.myplus.analytics.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.myplus.common.web.exception.ValidationException;

/**
 * AN-1 — finance's P&L, month by month ({@code GET /api/finance/gl/pnl/monthly}), as the caller.
 *
 * <p>Three answers, kept apart because they mean different things to the reader:
 * finance REFUSED the caller (401/403) → {@link AccessDeniedException}, and analytics serves nothing;
 * finance REJECTED the range (400) → {@link ValidationException} with finance's words;
 * finance could not answer (down, timeout, 5xx) → {@link Unavailable}, and analytics may serve what it stored.
 */
@Component
public class FinanceMetricsClient {

    /** One month of finance's P&L. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Month(String month, LocalDate from, LocalDate to,
                        BigDecimal totalIncome, BigDecimal totalExpense, BigDecimal netProfit) {}

    /** finance could not answer; the reason is for the log, not the screen. */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String reason, Throwable cause) { super(reason, cause); }
    }

    private final RestClient finance;

    public FinanceMetricsClient(@Qualifier("financeRestClient") RestClient finance) {
        this.finance = finance;
    }

    public List<Month> monthly(YearMonth from, YearMonth to) {
        try {
            List<Month> months = finance.get()
                    .uri("/api/finance/gl/pnl/monthly?from={f}&to={t}", from.toString(), to.toString())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Month>>() {});
            if (months == null) throw new Unavailable("finance answered with no body", null);
            return months;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value() || e.getStatusCode().value() == HttpStatus.FORBIDDEN.value())
                throw new AccessDeniedException("finance refused the caller");
            if (e.getStatusCode().value() == HttpStatus.BAD_REQUEST.value())
                throw new ValidationException(messageOf(e));
            throw new Unavailable("finance answered " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new Unavailable("finance did not answer: " + e.getMessage(), e);
        }
    }

    /** finance's own words from its ApiResponse body ({@code "message"}), else a plain sentence. */
    private static String messageOf(HttpClientErrorException e) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.getResponseBodyAsString());
            String m = node.path("message").asText("");
            return m.isBlank() ? "The months asked for were refused." : m;
        } catch (Exception ignored) {
            return "The months asked for were refused.";
        }
    }
}
