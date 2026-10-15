package com.myplus.business_service.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.dto.SellDTO;
import com.myplus.business_service.entity.Area;
import com.myplus.business_service.entity.City;
import com.myplus.business_service.entity.CustomerHistory;
import com.myplus.business_service.entity.Sell;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.repository.AreaRepository;
import com.myplus.business_service.repository.CityRepository;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.dto.ProductRef;

/**
 * SRA-4a — the sales-by-rep-and-area dimensions of the Sale Detail Report, and who may see which of its rows.
 * Design: {@code microservices/docs/slices/sra-sales-by-rep-and-area.md} §7.
 *
 * <p><b>Batch reads only.</b> The report already loads every line of the period; this adds ONE query per dimension
 * (areas, cities, suppliers, first purchases), never one per line.
 *
 * <p>The rules, each the one another screen already uses, so two reports never disagree:
 * <ul>
 *   <li><b>Area / city</b> — the customer's CURRENT area (owner's decision: moving a customer moves their history).</li>
 *   <li><b>Supplier</b> — the catalog's main supplier, else the product's first purchase (the SSR rule).</li>
 *   <li><b>Bonus value</b> — bonus qty × TP, the product's last purchase rate (the SSR convention).</li>
 * </ul>
 */
@Service
public class SaleReportDimensions {

    private final AreaRepository areas;
    private final CityRepository cities;
    private final VenderRepo venders;
    private final PurchaseRepo purchases;
    private final SalesRepService reps;
    private final RequestUtil requestUtil;

    public SaleReportDimensions(AreaRepository areas, CityRepository cities, VenderRepo venders, PurchaseRepo purchases,
                                SalesRepService reps, RequestUtil requestUtil) {
        this.areas = areas;
        this.cities = cities;
        this.venders = venders;
        this.purchases = purchases;
        this.reps = reps;
        this.requestUtil = requestUtil;
    }

    /**
     * Who may see which sale lines (R11 — the permission set's "Sees" scope, and its store grants).
     *
     * <pre>
     *   Sees: All, no store restriction → every line
     *   otherwise                        → lines of an invoice in a store they may access, AND (Sees: All, or the
     *                                      invoice was KEYED by them, or it is credited to the rep their login is)
     * </pre>
     * ⚠ The report used to show the whole business to anyone who could open it — a cashier on a "Sees: Own" set, and a
     * branch member restricted to one store, both read every sale of every store.
     */
    @Transactional(readOnly = true)
    public List<Sell> visible(List<Sell> lines, Long orgId, Long userId) {
        boolean all = requestUtil.callerSeesWholeOrg();
        if (all && requestUtil.accessibleStoreIds().isEmpty()) return lines;
        Long myRep = all ? null : reps.repForUser(orgId, userId).map(r -> r.getId()).orElse(null);
        List<Sell> out = new ArrayList<>();
        for (Sell s : lines) {
            CustomerHistory ch = s.getCustomerHistory();
            if (ch == null) continue;   // a line with no invoice belongs to no one's report
            if (!requestUtil.canAccessStore(ch.getStoreId())) continue;
            if (all || Objects.equals(ch.getUserId(), userId) || (myRep != null && myRep.equals(ch.getRepId())))
                out.add(s);
        }
        return out;
    }

    /**
     * Fills area, city, supplier and bonus value on each row. {@code rows} carry {@code areaId} and {@code productId}
     * already (loadSR copies them from the invoice's customer and the line).
     */
    @Transactional(readOnly = true)
    public void enrich(List<SellDTO> rows, Map<Long, ProductRef> products, Long orgId, Long userId) {
        if (rows == null || rows.isEmpty()) return;

        // Area → its name and city.
        Set<Long> areaIds = new HashSet<>();
        for (SellDTO r : rows) if (r.getAreaId() != null) areaIds.add(r.getAreaId());
        Map<Long, Area> areaById = new HashMap<>();
        if (!areaIds.isEmpty()) for (Area a : areas.findAllScoped(areaIds, orgId)) areaById.put(a.getId(), a);
        Map<Long, String> cityName = new HashMap<>();
        if (!areaById.isEmpty()) for (City c : cities.findByOrg(orgId)) cityName.put(c.getId(), c.getName());

        // Product → its supplier: the catalog's main supplier, else the first purchase's (SSR rule).
        Set<Long> productIds = new HashSet<>();
        for (SellDTO r : rows) if (r.getProductId() != null) productIds.add(r.getProductId());
        Map<Long, Long> supplierOf = new HashMap<>();
        if (!productIds.isEmpty()) for (Object[] f : purchases.firstSupplierByProduct(orgId, productIds)) {
            if (f[0] != null && f[1] != null) supplierOf.put(((Number) f[0]).longValue(), ((Number) f[1]).longValue());
        }
        products.forEach((id, p) -> { if (p != null && p.getMainSupplierId() != null) supplierOf.put(id, p.getMainSupplierId()); });
        Map<Long, String> supplierName = new HashMap<>();
        if (!supplierOf.isEmpty()) for (Vender v : venders.findScoped(orgId, userId)) supplierName.put(v.getId(), v.getName());

        for (SellDTO r : rows) {
            Area a = r.getAreaId() == null ? null : areaById.get(r.getAreaId());
            if (a == null) {
                r.setAreaId(null);   // an id that does not resolve in THIS business is no area at all
            } else {
                r.setArea(a.getName());
                r.setCityId(a.getCityId());
                r.setCity(cityName.get(a.getCityId()));
            }
            Long sup = r.getProductId() == null ? null : supplierOf.get(r.getProductId());
            if (sup != null && supplierName.containsKey(sup)) {
                r.setSupplierId(sup);
                r.setSupplier(supplierName.get(sup));
            }
            r.setBonusValue(bonusValue(r.getBonusQuantity(), products.get(r.getProductId())));
        }
    }

    /** bonus qty × TP, to the cent; zero when there is no bonus or no TP yet (never bought). */
    static BigDecimal bonusValue(BigDecimal bonusQty, ProductRef p) {
        if (bonusQty == null || bonusQty.signum() <= 0 || p == null || p.getLastPurchaseRate() == null)
            return BigDecimal.ZERO.setScale(2);
        return bonusQty.multiply(p.getLastPurchaseRate()).setScale(2, RoundingMode.HALF_UP);
    }
}
