package com.myplus.business_service.service;

import java.math.BigDecimal;

/**
 * Tax breakdown for one sale line (G3, slice 35): the taxable {@code net}, the applied {@code rate} (%),
 * the {@code tax} amount, and the {@code gross} the customer pays for the line ({@code net + tax}).
 */
public record TaxResult(BigDecimal net, BigDecimal rate, BigDecimal tax, BigDecimal gross) {}
