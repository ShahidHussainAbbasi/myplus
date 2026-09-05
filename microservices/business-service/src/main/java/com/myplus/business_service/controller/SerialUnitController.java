package com.myplus.business_service.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
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

    /** One unit as the screens need it. Deliberately flat — there is no nested shape worth inventing here. */
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
