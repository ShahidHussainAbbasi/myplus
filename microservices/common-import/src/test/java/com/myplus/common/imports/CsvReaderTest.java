package com.myplus.common.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Slice I1 — the parser, which is where an import silently mis-columns data if it is wrong.
 *
 * <p>Pure: no Spring, no container, no Docker. Runs on every {@code mvn test}, which is the standing rule.
 */
class CsvReaderTest {

    private static final int CAP = 100;

    @Test
    void reads_a_plain_file() {
        CsvReader.ParsedFile f = CsvReader.parse("name,contact\nAli,0300\nSara,0301\n", CAP);

        assertEquals(2, f.getRows().size());
        assertEquals("Ali", f.getRows().get(0).get("name"));
        assertEquals("0301", f.getRows().get(1).get("contact"));
    }

    @Test
    void row_numbers_match_the_spreadsheet_the_operator_is_looking_at() {
        CsvReader.ParsedFile f = CsvReader.parse("name\nA\nB\n", CAP);

        // Header is line 1, so the first data row is line 2. An error message quoting line 1 for the first
        // customer would send the operator to the header.
        assertEquals(2, f.getRows().get(0).getLineNumber());
        assertEquals(3, f.getRows().get(1).getLineNumber());
    }

    @Test
    void a_quoted_field_may_contain_the_delimiter() {
        CsvReader.ParsedFile f = CsvReader.parse("name,address\n\"Khan, Ali\",Lahore\n", CAP);

        assertEquals("Khan, Ali", f.getRows().get(0).get("name"));
        assertEquals("Lahore", f.getRows().get(0).get("address"));
    }

    @Test
    void a_doubled_quote_is_a_literal_quote() {
        CsvReader.ParsedFile f = CsvReader.parse("name\n\"Ali \"\"Bhai\"\" Khan\"\n", CAP);

        assertEquals("Ali \"Bhai\" Khan", f.getRows().get(0).get("name"));
    }

    @Test
    void a_quoted_field_may_contain_a_newline() {
        // The case a line-based parser gets wrong: this is ONE row, not two.
        CsvReader.ParsedFile f = CsvReader.parse("name,address\nAli,\"Street 1\nLahore\"\n", CAP);

        assertEquals(1, f.getRows().size());
        assertEquals("Street 1\nLahore", f.getRows().get(0).get("address"));
    }

    @Test
    void accepts_crlf_and_lf_alike() {
        assertEquals(2, CsvReader.parse("name\r\nAli\r\nSara\r\n", CAP).getRows().size());
        assertEquals(2, CsvReader.parse("name\nAli\nSara", CAP).getRows().size());
    }

    @Test
    void strips_the_excel_byte_order_mark() {
        // Without this the first header reads "﻿name" and every row looks like it is missing the column.
        CsvReader.ParsedFile f = CsvReader.parse("﻿name,contact\nAli,0300\n", CAP);

        assertEquals("name", f.getHeaders().get(0));
        assertEquals("Ali", f.getRows().get(0).get("name"));
    }

    @Test
    void ignores_blank_trailing_lines_spreadsheets_add() {
        CsvReader.ParsedFile f = CsvReader.parse("name\nAli\n\n\n,\n", CAP);

        assertEquals(1, f.getRows().size());
    }

    @Test
    void keeps_a_ragged_row_so_the_spec_can_refuse_it_by_line_number() {
        // Dropping it here would lose the row number, and the operator would never learn which line was wrong.
        CsvReader.ParsedFile f = CsvReader.parse("name,contact\nAli\n", CAP);

        assertEquals(1, f.getRows().size());
        assertEquals("Ali", f.getRows().get(0).get("name"));
        assertNull(f.getRows().get(0).get("contact"), "absent cell reads as null, not empty string");
    }

    @Test
    void a_blank_cell_reads_as_null_not_whitespace() {
        CsvReader.ParsedFile f = CsvReader.parse("name,email\nAli,   \n", CAP);

        assertNull(f.getRows().get(0).get("email"));
    }

    @Test
    void refuses_more_rows_than_the_cap_rather_than_truncating() {
        StringBuilder sb = new StringBuilder("name\n");
        for (int i = 0; i < 12; i++) sb.append("row").append(i).append('\n');

        // Truncating would look like success while silently dropping the tail — the worst outcome available.
        CsvReader.CsvFormatException e = assertThrows(CsvReader.CsvFormatException.class,
                () -> CsvReader.parse(sb.toString(), 10));
        assertTrue(e.getMessage().contains("10"));
    }

    @Test
    void refuses_an_unclosed_quote() {
        assertThrows(CsvReader.CsvFormatException.class, () -> CsvReader.parse("name\n\"Ali\n", CAP));
    }

    @Test
    void refuses_an_empty_file() {
        assertThrows(CsvReader.CsvFormatException.class, () -> CsvReader.parse("   ", CAP));
        assertThrows(CsvReader.CsvFormatException.class, () -> CsvReader.parse(null, CAP));
    }
}
