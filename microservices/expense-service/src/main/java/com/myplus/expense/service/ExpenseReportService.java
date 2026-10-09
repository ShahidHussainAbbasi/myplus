package com.myplus.expense.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.common.security.time.TenantClock;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.expense.entity.ExpenseVoucher;
import com.myplus.expense.entity.ExpenseVoucherLine;
import com.myplus.expense.repository.ExpenseVoucherRepo;

import lombok.RequiredArgsConstructor;

/**
 * EX-8a — the expense report. Built line by line on the P&L's own rule, so its total for a period IS the P&L's expense
 * total for the expenses recorded here: an expense in the books (POSTED_GL) counts on its own date; a void counts as a
 * NEGATIVE line on the day its reversal is dated ({@code void_posted_on}); an expense the books refused or have not yet
 * taken is not in it. Scoped like the list: owner/admin the business, a user their own.
 */
@Service
@RequiredArgsConstructor
public class ExpenseReportService {

    /** Longest period one report covers: two years of lines is already more than a screen wants. */
    static final long MAX_DAYS = 731;

    public record Entry(LocalDate date, String voucherNo, String categoryName, String accountCode, String paidFrom,
                        String payee, Long userId, BigDecimal amount, boolean isVoid) { }

    public record Group(String key, String label, BigDecimal amount, long count) { }

    public record Summary(LocalDate from, LocalDate to, String by, BigDecimal total, long count, List<Group> groups) { }

    private final ExpenseVoucherRepo vouchers;
    private final ExpenseAccess access;
    private final StaffDirectory staff;

    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to, String by) {
        LocalDate[] p = period(from, to);
        String grouping = by == null || by.isBlank() ? "category" : by.trim();
        List<Entry> entries = entries(p[0], p[1]);
        Function<Entry, String[]> keyOf = switch (grouping) {
            case "category" -> e -> new String[]{ e.accountCode() + ":" + e.categoryName(), e.categoryName() + " (" + e.accountCode() + ")" };
            case "member" -> memberKey();
            case "month" -> e -> new String[]{ e.date().toString().substring(0, 7),
                    e.date().format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)) };
            case "paidFrom" -> e -> new String[]{ e.paidFrom(), paidFromLabel(e.paidFrom()) };
            default -> throw new ValidationException("Group the report by category, member, month or paid from.");
        };
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, BigDecimal> sums = new HashMap<>();
        Map<String, Long> counts = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Entry e : entries) {
            String[] k = keyOf.apply(e);
            labels.putIfAbsent(k[0], k[1]);
            sums.merge(k[0], e.amount(), BigDecimal::add);
            counts.merge(k[0], 1L, Long::sum);
            total = total.add(e.amount());
        }
        List<Group> groups = new ArrayList<>();
        labels.forEach((k, l) -> groups.add(new Group(k, l, sums.get(k), counts.get(k))));
        groups.sort("month".equals(grouping) ? Comparator.comparing(Group::key)
                : Comparator.comparing(Group::amount).reversed().thenComparing(Group::label));
        return new Summary(p[0], p[1], grouping, total, entries.size(), groups);
    }

    /** One CSV row per line, a void as its own negative row: it sums to {@link #summary} for the same period. */
    @Transactional(readOnly = true)
    public String csv(LocalDate from, LocalDate to) {
        LocalDate[] p = period(from, to);
        Function<Entry, String[]> member = memberKey();
        StringBuilder out = new StringBuilder("Date,Number,Category,Account,Paid from,Payee,Member,Amount,Kind\r\n");
        entries(p[0], p[1]).stream().sorted(Comparator.comparing(Entry::date).thenComparing(e -> String.valueOf(e.voucherNo())))
                .forEach(e -> out.append(e.date()).append(',')
                        .append(cell(e.voucherNo())).append(',')
                        .append(cell(e.categoryName())).append(',')
                        .append(cell(e.accountCode())).append(',')
                        .append(cell(paidFromLabel(e.paidFrom()))).append(',')
                        .append(cell(e.payee())).append(',')
                        .append(cell(member.apply(e)[1])).append(',')
                        .append(e.amount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()).append(',')
                        .append(e.isVoid() ? "Void" : "Expense").append("\r\n"));
        return out.toString();
    }

    /** Every line in the books for the period, voids as negatives on their own day. */
    List<Entry> entries(LocalDate from, LocalDate to) {
        access.assertModuleOn();
        Long org = access.org(), only = access.visibleUserId();
        List<Entry> out = new ArrayList<>();
        for (ExpenseVoucher v : vouchers.postedInRange(org, only, from, to))
            for (ExpenseVoucherLine l : v.getLines()) out.add(entry(v, l, v.getVoucherDate(), false));
        for (ExpenseVoucher v : vouchers.voidsInRange(org, only, from, to))
            for (ExpenseVoucherLine l : v.getLines()) out.add(entry(v, l, v.getVoidPostedOn(), true));
        return out;
    }

    private static Entry entry(ExpenseVoucher v, ExpenseVoucherLine l, LocalDate on, boolean isVoid) {
        BigDecimal amt = l.getAmount() == null ? BigDecimal.ZERO : l.getAmount();
        return new Entry(on, v.getVoucherNo(), l.getCategoryName(), l.getAccountCode(), v.getPaidFrom(), v.getPayeeName(),
                v.getUserId(), isVoid ? amt.negate() : amt, isVoid);
    }

    /** The member a line belongs to: named from auth's staff list for an owner/admin; "You" for a member's own report. */
    private Function<Entry, String[]> memberKey() {
        if (!access.canApprove()) return e -> new String[]{ String.valueOf(e.userId()), "You" };
        Map<Long, String> names = new HashMap<>();
        try {
            staff.staff().forEach(m -> names.put(m.userId(), m.name()));
        } catch (Exception unreachable) {
            // the report still adds up without names; a member then reads as their number
        }
        return e -> new String[]{ String.valueOf(e.userId()),
                e.userId() == null ? "—" : names.getOrDefault(e.userId(), "Member #" + e.userId()) };
    }

    static String paidFromLabel(String paidFrom) {
        if (paidFrom == null) return "—";
        return switch (paidFrom) {
            case "CASH" -> "Cash";
            case "BANK" -> "Bank";
            case "DRAWER" -> "Till";
            case "AP" -> "Bill";
            case "EMPLOYEE" -> "Claim";
            default -> paidFrom;
        };
    }

    /** A CSV cell: quoted when it holds a comma, quote or line break; a leading = + - @ is defused (formula injection). */
    static String cell(String s) {
        if (s == null) return "";
        String v = s;
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    /** From/to default to this month so far; to must not be before from, and one report covers at most two years. */
    static LocalDate[] period(LocalDate from, LocalDate to) {
        LocalDate t = to != null ? to : TenantClock.today();
        LocalDate f = from != null ? from : t.withDayOfMonth(1);
        if (t.isBefore(f)) throw new ValidationException("The report's end date is before its start date.");
        if (ChronoUnit.DAYS.between(f, t) > MAX_DAYS) throw new ValidationException("A report covers at most two years. Choose a shorter period.");
        return new LocalDate[]{ f, t };
    }
}
