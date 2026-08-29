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
        String normalised = SerialUnitService.normalise(serial);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SerialUnit u : serialUnitRepo.findHistory(org, normalised)) {
            rows.add(row(u));
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
        m.put("dated", u.getDated() != null ? u.getDated().toString() : null);
        return m;
    }
}
