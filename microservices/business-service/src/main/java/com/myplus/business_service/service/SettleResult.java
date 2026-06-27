package com.myplus.business_service.service;

import java.math.BigDecimal;

/**
 * Outcome of settling a sale against its grand total (G5, slice 37): how much was {@code paid} (non-credit
 * tenders), the {@code due} remaining, cash {@code change}, the total {@code tendered}, and the summary
 * {@code paymentMode} (a single method name, or {@code SPLIT}, or null when nothing was tendered).
 */
public record SettleResult(BigDecimal paid, BigDecimal due, BigDecimal change, BigDecimal tendered, String paymentMode) {}
