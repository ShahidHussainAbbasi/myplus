package com.myplus.common.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RFC-4180 CSV reader (slice I1) — the missing half of {@code CsvWriter}.
 *
 * <h3>Why this exists rather than a split on commas</h3>
 * Quoting is the whole job. A customer name containing a comma, a quote or a newline must not shift every later
 * column or truncate the file — exactly the reason {@code CsvWriter} is a component rather than a string-join.
 * Reading has the same requirement in reverse, and gets it wrong more quietly: a naive split produces rows that
 * look plausible and are silently mis-columned.
 *
 * <h3>Deliberately pure</h3>
 * No Spring, no IO, no persistence — text in, rows out. Every rule below is therefore unit-testable without a
 * container, which is the standing requirement that a slice ships tests that run on every {@code mvn test}.
 *
 * <h3>What it does NOT do</h3>
 * It does not validate, default, coerce or repair. A blank cell is a blank cell; a ragged row keeps whatever
 * cells it had. Deciding whether that is acceptable belongs to the {@link ImportSpec}, because only the spec
 * knows which columns are required. Keeping the parser opinion-free is what lets one reader serve every entity.
 */
public final class CsvReader {

    /** A UTF-8 byte-order mark, which Excel writes and which would otherwise corrupt the first header. */
    private static final char BOM = '﻿';

    private CsvReader() {}

    /** One parsed data row: its 1-based line number in the file, and its cells keyed by header. */
    public static final class Row {
        private final int lineNumber;
        private final Map<String, String> values;

        Row(int lineNumber, Map<String, String> values) {
            this.lineNumber = lineNumber;
            this.values = values;
        }

        /** 1-based, counting the header — so it matches what the operator sees in their spreadsheet. */
        public int getLineNumber() { return lineNumber; }

        /** Trimmed cell for a header, or {@code null} when the column was absent or blank. */
        public String get(String header) {
            String v = values.get(header);
            if (v == null) return null;
            String t = v.trim();
            return t.isEmpty() ? null : t;
        }

        public Map<String, String> getValues() { return values; }
    }

    /** Headers as written in the file, plus the data rows. */
    public static final class ParsedFile {
        private final List<String> headers;
        private final List<Row> rows;

        ParsedFile(List<String> headers, List<Row> rows) {
            this.headers = headers;
            this.rows = rows;
        }

        public List<String> getHeaders() { return headers; }
        public List<Row> getRows() { return rows; }
    }

    /** Raised for a file that cannot be read at all — as distinct from a file whose rows are invalid. */
    public static class CsvFormatException extends RuntimeException {
        public CsvFormatException(String message) { super(message); }
    }

    /**
     * Parse a whole file.
     *
     * @param text    the file's contents; CRLF and LF are both accepted, and a leading BOM is dropped
     * @param maxRows hard cap on DATA rows. Exceeding it throws rather than truncating: silently importing the
     *                first 5 000 of 8 000 rows is the worst possible outcome, because it looks like success.
     */
    public static ParsedFile parse(String text, int maxRows) {
        if (text == null || text.trim().isEmpty())
            throw new CsvFormatException("The file is empty.");

        List<List<String>> records = split(text);
        if (records.isEmpty())
            throw new CsvFormatException("The file is empty.");

        List<String> headers = new ArrayList<>();
        for (String h : records.get(0)) headers.add(h == null ? "" : h.trim());
        if (headers.isEmpty() || headers.stream().allMatch(String::isEmpty))
            throw new CsvFormatException("The first line must be the column headers from the template.");

        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> cells = records.get(i);

            // A wholly blank line is skipped, not reported: spreadsheets append them constantly and an operator
            // has no way to see or remove one. A line with ANY content is kept, even if ragged, so the spec can
            // refuse it with a row number the operator can actually find.
            if (cells.stream().allMatch(c -> c == null || c.trim().isEmpty())) continue;

            if (rows.size() >= maxRows)
                throw new CsvFormatException("This file has more than " + maxRows
                        + " rows. Split it into smaller files and import them one at a time.");

            Map<String, String> values = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++)
                values.put(headers.get(c), c < cells.size() ? cells.get(c) : null);

            // +1 because records are 0-based and the operator's spreadsheet counts the header as line 1.
            rows.add(new Row(i + 1, values));
        }
        return new ParsedFile(headers, rows);
    }

    /**
     * The parser proper: a character scanner, because quoted fields may contain the delimiter AND the line
     * break, so neither a line-split nor a regex can be correct here.
     */
    private static List<List<String>> split(String text) {
        String s = (!text.isEmpty() && text.charAt(0) == BOM) ? text.substring(1) : text;

        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);

            if (inQuotes) {
                if (ch == '"') {
                    // A doubled quote inside a quoted field is a literal quote; a single one closes the field.
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else inQuotes = false;
                } else {
                    field.append(ch);
                }
                continue;
            }

            switch (ch) {
                case '"':
                    inQuotes = true;
                    break;
                case ',':
                    record.add(field.toString());
                    field.setLength(0);
                    break;
                case '\r':
                    // CRLF or a lone CR both end the record; swallow the LF so it does not open an empty one.
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                    record.add(field.toString());
                    field.setLength(0);
                    records.add(record);
                    record = new ArrayList<>();
                    break;
                case '\n':
                    record.add(field.toString());
                    field.setLength(0);
                    records.add(record);
                    record = new ArrayList<>();
                    break;
                default:
                    field.append(ch);
            }
        }

        if (inQuotes)
            throw new CsvFormatException("The file has an unclosed quote — check for a stray \" character.");

        // The last record has no trailing newline to close it.
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
