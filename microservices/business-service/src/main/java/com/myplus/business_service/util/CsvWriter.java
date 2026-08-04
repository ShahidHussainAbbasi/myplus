package com.myplus.business_service.util;

import java.util.Collection;
import java.util.List;

/**
 * Minimal RFC-4180 CSV writer (slice b2b-P3d = requirement #5).
 *
 * <p>Deliberately generic — headers plus rows of anything — because <b>3e exports every report through this
 * same component</b>. A statement-specific serialiser would be the duplication 3e exists to prevent.
 *
 * <p>Quoting is the whole job and the reason this is not a string-join in a controller: a customer name
 * containing a comma, a quote, or a newline must not shift every later column or truncate the file. Fields
 * are quoted whenever they contain a delimiter, a quote or a line break, and embedded quotes are doubled.
 */
public final class CsvWriter {

    private static final String SEPARATOR = ",";
    private static final String NEWLINE = "\r\n";   // RFC-4180; Excel is happiest with CRLF

    private CsvWriter() {}

    /** Render a whole file: one header row plus one row per record. */
    public static String write(List<String> headers, Collection<? extends List<?>> rows) {
        StringBuilder sb = new StringBuilder();
        if (headers != null && !headers.isEmpty()) sb.append(row(headers)).append(NEWLINE);
        if (rows != null) {
            for (List<?> r : rows) sb.append(row(r)).append(NEWLINE);
        }
        return sb.toString();
    }

    /** Render one row. Nulls become empty fields — never the text "null" on a customer's statement. */
    public static String row(List<?> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(SEPARATOR);
            sb.append(escape(values.get(i)));
        }
        return sb.toString();
    }

    /**
     * Quote a field if it could otherwise break the row, doubling any embedded quote.
     *
     * <p>The leading-formula guard is deliberate: a field starting {@code = + - @} is executed by Excel and
     * Sheets when the file is opened (CSV injection). A statement is a document we hand to a customer, so it
     * must not be able to run anything on their machine.
     */
    static String escape(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        if (s.isEmpty()) return "";
        if ("=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;   // neutralise spreadsheet formula injection
        boolean needsQuotes = s.contains(SEPARATOR) || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needsQuotes) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
