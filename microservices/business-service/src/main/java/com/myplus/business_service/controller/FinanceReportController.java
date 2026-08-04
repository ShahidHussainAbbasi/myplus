package com.myplus.business_service.controller;

import com.myplus.business_service.service.FinanceReportService;
import com.myplus.business_service.util.GenericResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * F2: AR/AP statements + aging reports (read-only). Aging is computed from the tenant's open docs; a statement
 * merges the party's docs with the shared finance ledger. All reads are tenant-scoped inside FinanceReportService.
 */
@Controller
public class FinanceReportController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private FinanceReportService reportService;

    @Autowired
    private com.myplus.business_service.service.TaxBreakdownService taxBreakdownService;   // multi-rate tax: per-rate split

    /** Multi-rate tax: taxable + tax grouped by rate over [from,to] (output = sales, input = purchases). Sourced from
     *  the transactional lines; the finance GL register stays the authoritative net-payable summary. */
    @RequestMapping(value = "/taxBreakdown", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse taxBreakdown(@RequestParam(value = "from", required = false) String from,
                                        @RequestParam(value = "to", required = false) String to) {
        try {
            java.time.LocalDate f = (from == null || from.isBlank()) ? null : java.time.LocalDate.parse(from);
            java.time.LocalDate t = (to == null || to.isBlank()) ? null : java.time.LocalDate.parse(to);
            return new GenericResponse("SUCCESS", "Tax breakdown", taxBreakdownService.breakdown(f, t));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > taxBreakdown " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not load the tax breakdown.");
        }
    }

    /** AR aging — outstanding per customer in 0–30/31–60/61–90/90+ buckets. */
    @RequestMapping(value = "/customerAging", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse customerAging() {
        try {
            return new GenericResponse("SUCCESS", "Customer aging", reportService.customerAging());
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > customerAging " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not load customer aging.");
        }
    }

    /** AP aging — outstanding per vendor in 0–30/31–60/61–90/90+ buckets. */
    @RequestMapping(value = "/vendorAging", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse vendorAging() {
        try {
            return new GenericResponse("SUCCESS", "Vendor aging", reportService.vendorAging());
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > vendorAging " + e.getMessage(), e);
            return new GenericResponse("ERROR", "Could not load vendor aging.");
        }
    }

    /** AR statement of account for one customer (bills + receipts, running balance). */
    @RequestMapping(value = "/customerStatement", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse customerStatement(@RequestParam("customerId") Long customerId) {
        try {
            return new GenericResponse("SUCCESS", "Customer statement", reportService.customerStatement(customerId));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > customerStatement " + e.getMessage(), e);
            return new GenericResponse("FAILED", "Could not load the customer statement.");
        }
    }

    /**
     * B2B-P3d (#5): the SAME customer statement, as a downloadable CSV.
     *
     * <p>Calls the identical service method the JSON endpoint calls, so the file a customer reconciles
     * against can never disagree with what the screen shows — the reason this is an adapter over the
     * existing method rather than a second query. Tenant scope and the anti-IDOR customer check live in
     * {@code customerStatement(...)} and therefore apply here unchanged: a CSV route must never become a way
     * to read another tenant's ledger.
     */
    @RequestMapping(value = "/customerStatement.csv", method = RequestMethod.GET,
            produces = "text/csv; charset=UTF-8")
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> customerStatementCsv(
            @RequestParam("customerId") Long customerId) {
        try {
            return csv(statementCsv(reportService.customerStatement(customerId)),
                    "customer-statement-" + customerId + ".csv");
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > customerStatementCsv " + e.getMessage(), e);
            return org.springframework.http.ResponseEntity.status(400).body("Could not build the statement.");
        }
    }

    /** B2B-P3d (#5): the vendor statement as a downloadable CSV — same adapter, same guarantees. */
    @RequestMapping(value = "/vendorStatement.csv", method = RequestMethod.GET,
            produces = "text/csv; charset=UTF-8")
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> vendorStatementCsv(
            @RequestParam("venderId") Long venderId) {
        try {
            return csv(statementCsv(reportService.vendorStatement(venderId)),
                    "vendor-statement-" + venderId + ".csv");
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > vendorStatementCsv " + e.getMessage(), e);
            return org.springframework.http.ResponseEntity.status(400).body("Could not build the statement.");
        }
    }

    /** One statement -> CSV text. Column order matches the on-screen statement, deliberately. */
    private String statementCsv(java.util.List<com.myplus.common.subledger.StatementLine> lines) {
        java.util.List<java.util.List<?>> rows = new java.util.ArrayList<>();
        for (com.myplus.common.subledger.StatementLine l : lines) {
            rows.add(java.util.Arrays.asList(
                    l.getDate(), l.getDocNo(), l.getType(), l.getDebit(), l.getCredit(), l.getBalance()));
        }
        return com.myplus.business_service.util.CsvWriter.write(
                java.util.Arrays.asList("Date", "Document", "Type", "Debit", "Credit", "Balance"), rows);
    }

    /** Attachment response — the browser saves the file instead of rendering it. */
    private org.springframework.http.ResponseEntity<String> csv(String body, String filename) {
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(body);
    }

    /** AP statement of account for one vendor (bills + payments, running balance). */
    @RequestMapping(value = "/vendorStatement", method = RequestMethod.GET)
    @ResponseBody
    public GenericResponse vendorStatement(@RequestParam("venderId") Long venderId) {
        try {
            return new GenericResponse("SUCCESS", "Vendor statement", reportService.vendorStatement(venderId));
        } catch (Exception e) {
            LOGGER.error(getClass().getName() + " > vendorStatement " + e.getMessage(), e);
            return new GenericResponse("FAILED", "Could not load the vendor statement.");
        }
    }
}
