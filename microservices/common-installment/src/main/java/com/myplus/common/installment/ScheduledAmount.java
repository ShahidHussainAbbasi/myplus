package com.myplus.common.installment;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One dated obligation in a schedule: "installment 3 of 6, 8,000.00, due 15 April".
 *
 * <p>A top-level record rather than a nested one because it crosses boundaries — the counter previews a list
 * of these before the sale commits, the service persists them as {@code installment} rows, and the printed
 * agreement renders them. A type that three layers name deserves its own file.
 *
 * <p>Immutable on purpose: the schedule read out to a customer at the counter and the schedule stored against
 * the sale must be the same numbers. Nothing between the preview and the commit can adjust one without the
 * other.
 *
 * @param seqNo   1-based, because a customer is told "3 of 6" and never "2 of 6" for the third payment
 * @param dueDate the calendar date the money is expected, already clamped to the month's length
 * @param amount  {@code DECIMAL(19,2)}; the final installment carries the division's remainder
 */
public record ScheduledAmount(int seqNo, LocalDate dueDate, BigDecimal amount) {
}
