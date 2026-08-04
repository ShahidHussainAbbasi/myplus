package com.myplus.business_service.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B2B-P3d (#5): a statement is a document handed to a customer, so its CSV must survive real data. Pure
 * logic — runs on every {@code mvn test}.
 */
class CsvWriterTest {

    @Test
    @DisplayName("plain values are written unquoted")
    void plain() {
        assertEquals("INV-000001,100.00", CsvWriter.row(Arrays.asList("INV-000001", "100.00")));
    }

    @Test
    @DisplayName("a comma in a name does not shift every later column")
    void commaIsQuoted() {
        // "Acme, Inc." unquoted would turn a 3-column row into 4 and misalign the whole statement.
        assertEquals("\"Acme, Inc.\",50", CsvWriter.row(Arrays.asList("Acme, Inc.", "50")));
    }

    @Test
    @DisplayName("an embedded quote is doubled, not dropped")
    void quoteIsDoubled() {
        assertEquals("\"He said \"\"hi\"\"\"", CsvWriter.row(List.of("He said \"hi\"")));
    }

    @Test
    @DisplayName("a newline inside a field does not truncate the file")
    void newlineIsQuoted() {
        assertEquals("\"line1\nline2\"", CsvWriter.row(List.of("line1\nline2")));
    }

    @Test
    @DisplayName("null becomes an empty field, never the text 'null'")
    void nullIsEmpty() {
        assertEquals("a,,b", CsvWriter.row(Arrays.asList("a", null, "b")));
    }

    @Test
    @DisplayName("a leading = + - @ cannot execute when the customer opens the file")
    void formulaInjectionIsNeutralised() {
        // Excel and Sheets EXECUTE a field starting with these. We hand this file to customers.
        assertEquals("'=1+1", CsvWriter.escape("=1+1"));
        assertEquals("'+cmd", CsvWriter.escape("+cmd"));
        assertEquals("'-2+3", CsvWriter.escape("-2+3"));
        assertEquals("'@SUM(A1)", CsvWriter.escape("@SUM(A1)"));
    }

    @Test
    @DisplayName("a header row plus data rows, CRLF terminated")
    void wholeFile() {
        String csv = CsvWriter.write(Arrays.asList("Date", "Doc"),
                List.of(Arrays.asList("2026-08-03", "INV-000001")));
        assertEquals("Date,Doc\r\n2026-08-03,INV-000001\r\n", csv);
    }
}
