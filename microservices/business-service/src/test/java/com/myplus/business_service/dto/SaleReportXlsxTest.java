package com.myplus.business_service.dto;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SRA-4c — the workbook is the screen: what it is about, numbers as numbers, the tree as an Excel outline. */
class SaleReportXlsxTest {

    private static SellDTO row(String invoice, String rep, String area, String total) {
        SellDTO r = new SellDTO();
        r.setDated("15-10-2026");
        r.setInvoiceNo(invoice);
        r.setRepName(rep);
        r.setSaleSource(rep == null ? "COUNTER" : "REP_PAPER");
        r.setArea(area);
        r.setItemName("Davitran");
        r.setQuantity(BigDecimal.ONE);
        r.setTotalAmount(new BigDecimal(total));
        r.setTaxAmount(BigDecimal.ZERO);
        return r;
    }

    private static List<SellDTO> month() {
        return List.of(row("INV-1", "Irfan", "Zahir Pir", "3450.00"), row("INV-2", "Irfan", "1st Sector", "1150.00"),
                row("INV-3", null, "Model Town", "460.00"));
    }

    private static Map<String, String> about() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("Period", "01-10-2026 to 31-10-2026");
        a.put("Filters", "Rep #11");
        a.put("Grouping", "REP > AREA");
        return a;
    }

    @Test
    @DisplayName("grouped: the header says what it is; numbers are numbers; children fold under their parent; a grand total")
    void grouped() throws Exception {
        List<SaleReportGrouping> levels = SaleReportGrouping.levels("REP,AREA");
        byte[] file = SaleReportXlsx.grouped(about(), SaleReportGrouping.tree(month(), levels, null), levels, null);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sh = wb.getSheetAt(0);
            assertEquals("Sale Detail Report", sh.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Period", sh.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Rep #11", sh.getRow(2).getCell(1).getStringCellValue());
            int head = 5;   // title + 3 about rows + a blank row
            assertEquals("REP › AREA", sh.getRow(head).getCell(0).getStringCellValue());
            Row irfan = sh.getRow(head + 1);
            assertEquals("Irfan", irfan.getCell(0).getStringCellValue());
            int totalCol = 3;   // Invoices, Qty, Total
            assertEquals(CellType.NUMERIC, irfan.getCell(totalCol).getCellType(), "money is a NUMBER, not text");
            assertEquals(4600.00, irfan.getCell(totalCol).getNumericCellValue(), 0.001);
            assertEquals(1, sh.getRow(head + 2).getOutlineLevel(), "an area row folds under its rep");
            assertEquals(0, irfan.getOutlineLevel(), "the rep row is the top level");
            Row grand = sh.getRow(sh.getLastRowNum());
            assertEquals("Grand total", grand.getCell(0).getStringCellValue());
            assertEquals(5060.00, grand.getCell(totalCol).getNumericCellValue(), 0.001);
            assertEquals(3, grand.getCell(1).getNumericCellValue(), 0.001, "three distinct invoices");
        }
    }

    @Test
    @DisplayName("ungrouped: one row per sale line, the CSV's columns, totals as numbers")
    void detail() throws Exception {
        byte[] file = SaleReportXlsx.detail(about(), month());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sh = wb.getSheetAt(0);
            int head = 5;
            assertEquals("Date", sh.getRow(head).getCell(0).getStringCellValue());
            assertEquals("Rep", sh.getRow(head).getCell(11).getStringCellValue());
            assertEquals(head + 3, sh.getLastRowNum(), "three lines");
            assertEquals(3450.00, sh.getRow(head + 1).getCell(9).getNumericCellValue(), 0.001);
            assertEquals("COUNTER", sh.getRow(head + 3).getCell(12).getStringCellValue(), "a source is its code, as in the CSV");
        }
    }
}
