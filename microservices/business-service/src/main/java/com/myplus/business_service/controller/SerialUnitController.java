package com.myplus.business_service.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
// Imported rather than fully qualified: this class names a local variable `org`, which shadows
// the `org.*` package inside every method that declares it.
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.entity.SerialUnit;
import com.myplus.business_service.repository.SerialUnitRepo;
import com.myplus.business_service.service.SerialUnitService;
import com.myplus.common.security.CurrentUser;
import com.myplus.business_service.util.GenericResponse;

/**
 * SER-2 — reading the per-unit register.
 *
 * <h3>Why a read API ships WITH the register rather than after it</h3>
 * A register nobody can query is a table, not a feature. C6 shipped a per-product policy with no control on
 * any screen and every API test passed while the thing was unusable; the same mistake here would mean a shop
 * recording IMEIs it can never look up — which is the entire question a warranty claim, a return or a police
 * enquiry starts with.
 *
 * <h3>Scoped, always</h3>
 * Both reads take the tenant from {@code CurrentUser} and never from a parameter. A serial is exactly the sort
 * of identifier somebody would try across tenants, and an endpoint accepting an org id would be an IDOR with a
 * particularly unpleasant payload — "which shop is holding this handset".
 */
@RestController
public class SerialUnitController {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(SerialUnitController.class);

    /**
     * Product names for the condition list, resolved one PAGE at a time.
     *
     * <p>A serial without the model it belongs to answers half the question. Batched through the ref call the
     * purchase list already uses, so a page of 50 costs one round trip and not fifty.
     */
    @Autowired private com.myplus.commerce.contracts.client.CatalogClient catalogClient;

    @Autowired private SerialUnitRepo serialUnitRepo;

    /**
     * SER-2 (fix) — to resolve a PURCHASE invoice number into the units that bill brought in.
     *
     * <p>The register keys a sale by {@code invoiceNo} but a receipt only by {@code purchaseId}, so the bill
     * number an operator is holding cannot be matched without the purchase row. Reading it here rather than
     * denormalising the number onto every unit keeps one copy of the fact: a bill that is renumbered stays
     * findable, and a register row can never disagree with the bill it came from.
     */
    @Autowired private com.myplus.business_service.repository.PurchaseRepo purchaseRepo;

    /**
     * The units of a product currently on the shelf — what a cashier picks from when selling a tracked item.
     */
    @GetMapping("/serialUnits")
    public GenericResponse inStock(@RequestParam Long productId) {
        Long org = CurrentUser.organizationId();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SerialUnit u : serialUnitRepo.findInStock(org, productId)) {
            rows.add(row(u));
        }
        // The Collection overload, so the list lands in `collection` — the envelope every other list
        // endpoint here uses and the one the screens and fixtures already read.
        return new GenericResponse("SUCCESS", rows);
    }

    /**
     * Everything ever recorded under one serial, newest first.
     *
     * <p>The HISTORY, not just the live row: "who did we sell this handset to?" is asked about a unit that has
     * already left, and a query returning only what is in stock could never answer it. That is the specific
     * gap {@code InstallmentPlan.assetRef} left — it recorded a serial only while a plan was running.
     */
    @GetMapping("/serialHistory")
    public GenericResponse history(@RequestParam String serial) {
        Long org = CurrentUser.organizationId();
        String q = SerialUnitService.normalise(serial);
        if (q == null || q.isEmpty()) return new GenericResponse("SUCCESS", new ArrayList<Map<String, Object>>());

        // 1. The serial itself. The question this endpoint was built for, and still the common one.
        List<SerialUnit> hits = serialUnitRepo.findHistory(org, q);
        String matchedBy = "SERIAL";

        // 2. The number on a RECEIPT — which unit(s) left on this sale.
        if (hits.isEmpty()) {
            hits = serialUnitRepo.findBySaleInvoice(org, q);
            matchedBy = "SALE_INVOICE";
        }

        // 3. The number on a BILL — which units this delivery brought in.
        if (hits.isEmpty()) {
            List<Long> purchaseIds = purchaseRepo.findByInvoiceNoScoped(org, serial == null ? null : serial.trim())
                    .stream().map(com.myplus.business_service.entity.Purchase::getPurchaseId)
                    .filter(java.util.Objects::nonNull).toList();
            hits = purchaseIds.isEmpty() ? new ArrayList<SerialUnit>()
                    : serialUnitRepo.findByPurchaseIds(org, purchaseIds);
            matchedBy = "PURCHASE_INVOICE";
        }

        if (hits.isEmpty()) matchedBy = "NONE";

        /*
         * The BILL number each unit arrived on, resolved in ONE query for the whole result.
         *
         * The register stores purchaseId, not the number printed on the bill, and an id means nothing to the
         * person asking. Without this the answer to "where did this handset come from?" was an internal
         * primary key — technically the truth and of no use to anybody holding the document.
         */
        java.util.Map<Long, String> billNoById = new java.util.HashMap<>();
        java.util.List<Long> billIds = hits.stream().map(SerialUnit::getPurchaseId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!billIds.isEmpty()) {
            for (com.myplus.business_service.entity.Purchase b : purchaseRepo.findAllById(billIds)) {
                // Scoped on the way out as well as in: findAllById takes ids, and an id is exactly what an
                // IDOR supplies. Nothing here can leak a foreign bill number even if a unit row were wrong.
                if (org != null && !org.equals(b.getOrganizationId())) continue;
                billNoById.put(b.getPurchaseId(), b.getPurchaseInvoiceNo());
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SerialUnit u : hits) {
            Map<String, Object> m = row(u);
            m.put("purchaseInvoiceNo", billNoById.get(u.getPurchaseId()));
            // Carried on every row rather than in the envelope, because GenericResponse's collection form has
            // no place for a sibling field — and a caller that renders rows needs to know WHY they matched:
            // "3 units received on bill 10225" is a different sentence from "this handset's history".
            m.put("matchedBy", matchedBy);
            rows.add(m);
        }
        return new GenericResponse("SUCCESS", rows);
    }

    /**
     * SER-5 \u2014 how many units are on the shelf at each condition grade.
     *
     * <h3>Every grade is returned, including the empty ones</h3>
     * A grade with no units still gets a row with zero. Refurbished is zero for every tenant on the system
     * today, and a card that appears only once the first refurbished handset exists is a feature nobody
     * discovers \u2014 the shop has to know the grade is there before it books one in.
     */
    @GetMapping("/serialConditionCounts")
    public GenericResponse conditionCounts() {
        Long org = CurrentUser.organizationId();
        Map<String, Long> byGrade = new LinkedHashMap<>();
        for (String g : new String[] { SerialUnit.NEW, SerialUnit.USED, SerialUnit.REFURBISHED }) {
            byGrade.put(g, 0L);
        }
        for (Object[] row : serialUnitRepo.countInStockByCondition(org)) {
            String grade = row[0] == null ? SerialUnit.NEW : String.valueOf(row[0]);
            long n = row[1] == null ? 0L : ((Number) row[1]).longValue();
            // A grade the enum does not know about is still counted rather than dropped: the register is the
            // record, and a row we cannot classify is exactly the one somebody needs to see.
            byGrade.merge(grade, n, Long::sum);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : byGrade.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("grade", e.getKey());
            m.put("count", e.getValue());
            rows.add(m);
        }
        return new GenericResponse("SUCCESS", rows);
    }

    /**
     * SER-5 \u2014 the units at one grade, paged, with the detail a shopkeeper needs to act.
     *
     * <p>A serial on its own answers nothing: the question behind "show me the used stock" is which handset,
     * and which delivery it came in on. Both are resolved in ONE batch each, never per row \u2014 a page of 50
     * would otherwise cost 100 round trips.
     */
    @GetMapping("/serialUnitsByCondition")
    public GenericResponse byCondition(@RequestParam String grade,
                                       @RequestParam(required = false, defaultValue = "IN_STOCK") String status,
                                       @RequestParam(required = false, defaultValue = "0") int page,
                                       @RequestParam(required = false, defaultValue = "50") int size) {
        Long org = CurrentUser.organizationId();
        // Bounded rather than trusted: `size=100000` from a caller would be an unbounded read wearing a
        // parameter. 200 is comfortably above any page a person reads and well inside one response.
        int capped = Math.max(1, Math.min(size, 200));
        Page<SerialUnit> found = serialUnitRepo.findByCondition(
                org, SerialUnitService.normalise(grade), status == null ? "IN_STOCK" : status.trim().toUpperCase(),
                PageRequest.of(Math.max(0, page), capped));

        List<SerialUnit> units = found.getContent();

        // Product names, one call for the page.
        Map<Long, String> nameById = new HashMap<>();
        List<Long> productIds = units.stream().map(SerialUnit::getProductId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!productIds.isEmpty()) {
            try {
                for (com.myplus.commerce.contracts.dto.ProductRef ref : catalogClient.getProducts(productIds)) {
                    if (ref != null && ref.getId() != null) nameById.put(ref.getId(), ref.getName());
                }
            } catch (RuntimeException catalogDown) {
                // The register is the answer; a name is a convenience. A catalog hiccup must not empty a
                // screen the shop is using to find a handset.
                LOG.warn("Could not resolve product names for the condition list (rows still returned)", catalogDown);
            }
        }

        // Bill numbers, one call for the page — the register stores purchaseId, and an id means nothing to
        // the person holding the handset.
        Map<Long, String> billById = new HashMap<>();
        List<Long> purchaseIds = units.stream().map(SerialUnit::getPurchaseId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!purchaseIds.isEmpty()) {
            for (com.myplus.business_service.entity.Purchase b : purchaseRepo.findAllById(purchaseIds)) {
                if (org != null && !org.equals(b.getOrganizationId())) continue;   // scoped on the way out too
                billById.put(b.getPurchaseId(), b.getPurchaseInvoiceNo());
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SerialUnit u : units) {
            Map<String, Object> m = row(u);
            m.put("productName", nameById.get(u.getProductId()));
            m.put("purchaseInvoiceNo", billById.get(u.getPurchaseId()));
            rows.add(m);
        }

        /*
         * The page envelope rides on the FIRST ROW rather than beside the collection, because
         * GenericResponse's collection form has no sibling field for it. Every row carries the same values,
         * so a caller reads rows[0] — and a zero-row page still needs the totals, which is why they are also
         * returned when the list is empty.
         */
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page", found.getNumber());
        meta.put("size", capped);
        meta.put("totalElements", found.getTotalElements());
        meta.put("totalPages", found.getTotalPages());
        meta.put("hasNext", found.hasNext());
        meta.put("hasPrevious", found.hasPrevious());
        for (Map<String, Object> r : rows) r.put("_page", meta);
        if (rows.isEmpty()) {
            Map<String, Object> only = new LinkedHashMap<>();
            only.put("_page", meta);
            only.put("_empty", true);
            rows.add(only);
        }
        return new GenericResponse("SUCCESS", rows);
    }

    /** One unit as the screens need it. Deliberately flat \u2014 there is no nested shape worth inventing here. */
    private Map<String, Object> row(SerialUnit u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serialUnitId", u.getSerialUnitId());
        m.put("serialNo", u.getSerialNo());
        m.put("productId", u.getProductId());
        m.put("conditionGrade", u.getConditionGrade());
        m.put("status", u.getStatus());
        m.put("purchaseId", u.getPurchaseId());
        m.put("sellId", u.getSellId());
        /*
         * SER-3 — the invoice the unit left on.
         *
         * Omitted on the first pass because the field was added to the entity after this projection was
         * written, and nothing complained: the register was recording the answer correctly and the API simply
         * did not return it. The gate caught it only because it asserted the VALUE a caller receives rather
         * than the row in the table — "status is SOLD" passed happily while the question the whole register
         * exists to answer, WHICH SALE, came back undefined.
         */
        m.put("invoiceNo", u.getInvoiceNo());
        m.put("dated", u.getDated() != null ? u.getDated().toString() : null);
        return m;
    }
}
