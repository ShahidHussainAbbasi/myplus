package com.myplus.common.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * One column of an import template: its header, whether it is required, and what makes a value acceptable.
 *
 * <p>The same list generates the downloadable template AND validates the uploaded file. That is the point:
 * a header written in one place and parsed in another is how an import feature quietly stops round-tripping.
 */
public final class ColumnSpec {

    /** Dates are read and written in one unambiguous format — never the locale's, which differs per operator. */
    public static final String DATE_FORMAT = "yyyy-MM-dd";

    private final String header;
    private final boolean required;
    private final String hint;
    /** Returns an error message, or null when the value is acceptable. Never called with a blank value. */
    private final Function<String, String> validator;

    private ColumnSpec(String header, boolean required, String hint, Function<String, String> validator) {
        this.header = header;
        this.required = required;
        this.hint = hint;
        this.validator = validator;
    }

    public String getHeader() { return header; }
    public boolean isRequired() { return required; }
    /** Shown in the template's example row so an operator can see the expected shape. */
    public String getHint() { return hint; }

    /**
     * Validate one cell.
     *
     * @return an error message, or {@code null} when acceptable
     */
    public String validate(String value) {
        boolean blank = (value == null || value.trim().isEmpty());
        if (blank) return required ? ("'" + header + "' is required.") : null;
        return validator == null ? null : validator.apply(value.trim());
    }

    // ── factories ───────────────────────────────────────────────────────────────────────────────────────────

    public static ColumnSpec text(String header, boolean required, int maxLength, String hint) {
        return new ColumnSpec(header, required, hint, v -> {
            String formula = formulaRefusal(header, v);
            if (formula != null) return formula;
            if (v.length() > maxLength)
                return "'" + header + "' is longer than " + maxLength + " characters.";
            return null;
        });
    }

    public static ColumnSpec number(String header, boolean required, String hint) {
        return new ColumnSpec(header, required, hint, v -> {
            try {
                new BigDecimal(v.replace(",", ""));
                return null;
            } catch (NumberFormatException e) {
                return "'" + header + "' must be a number — got '" + v + "'.";
            }
        });
    }

    public static ColumnSpec integer(String header, boolean required, String hint) {
        return new ColumnSpec(header, required, hint, v -> {
            try {
                Integer.parseInt(v.trim());
                return null;
            } catch (NumberFormatException e) {
                return "'" + header + "' must be a whole number — got '" + v + "'.";
            }
        });
    }

    public static ColumnSpec date(String header, boolean required) {
        return new ColumnSpec(header, required, DATE_FORMAT, v -> {
            try {
                LocalDate.parse(v);
                return null;
            } catch (DateTimeParseException e) {
                return "'" + header + "' must be a date as " + DATE_FORMAT + " — got '" + v + "'.";
            }
        });
    }

    /** Case-insensitive on input; the spec is responsible for normalising when it builds the entity. */
    public static ColumnSpec oneOf(String header, boolean required, String... allowed) {
        List<String> values = Arrays.asList(allowed);
        return new ColumnSpec(header, required, String.join(" | ", allowed), v -> {
            for (String a : values) if (a.equalsIgnoreCase(v)) return null;
            return "'" + header + "' must be one of " + String.join(", ", values) + " — got '" + v + "'.";
        });
    }

    // ── the formula guard ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Refuse a TEXT value that Excel and Sheets would execute as a formula when the file is next opened.
     *
     * <p>{@code CsvWriter} already neutralises this on the way OUT, which protects documents we hand to
     * customers. This is the same threat on the way IN, and it is handled by <b>refusing rather than
     * rewriting</b>: an import that silently changed a customer's name would break §3.1's rule that a row is
     * created exactly as written or not at all. A name beginning {@code =} or {@code @} is a mistake or an
     * attack either way, and the operator should be told which row.
     *
     * <p>Applied to TEXT only, deliberately. A leading {@code -} is a perfectly good negative number, so the
     * numeric columns are guarded by being parsed as numbers — {@code =1+1} fails that parse on its own.
     */
    private static String formulaRefusal(String header, String v) {
        char c = v.charAt(0);
        if (c == '=' || c == '+' || c == '@')
            return "'" + header + "' starts with '" + c + "', which a spreadsheet would run as a formula. "
                    + "Remove the leading character.";
        return null;
    }
}
