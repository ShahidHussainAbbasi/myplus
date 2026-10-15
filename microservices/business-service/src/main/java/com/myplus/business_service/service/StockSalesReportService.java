package com.myplus.business_service.service;

import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.repository.PurchaseRepo;
import com.myplus.business_service.repository.SaleReturnRepo;
import com.myplus.business_service.repository.SellRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.client.CatalogClient;
import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.commerce.contracts.dto.ProductRef;
import com.myplus.commerce.contracts.dto.StockMovementType;
import com.myplus.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * SSR-1 (doc slices/110-stock-ledger-ssr.md) — the Stock & Sales Report: per product for a period, grouped by the
 * product's MAIN SUPPLIER, with subtotals and a Net Total — the report a pharmacy sends each supplier.
 *
 * <h3>Where each figure comes from</h3>
 * <ul>
 *   <li><b>Every QUANTITY from the stock ledger</b> (inventory, immutable, packs): opening, purchases, purchase
 *       returns, goods out on sales (paid + bonus together), sales returns, adjustments, closing. So each row adds up
 *       by construction: Closing = Opening + Purchase − Purchase return − Net sales − Bonus + Adjustments.</li>
 *   <li><b>MONEY and the bonus split from the sale documents</b>: sales value (lines dated in the period, plus the value
 *       of their later returns — a return reduces its line in place), sales-return value (credit notes dated in the
 *       period), bonus quantity (the lines' {@code bonus_quantity}).</li>
 *   <li><b>TP</b> = the product's last purchase rate per pack; bonus and closing are valued at it (the SSR convention).
 *       The books keep valuing stock at batch cost.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StockSalesReportService {

    static final String NO_SUPPLIER = "No supplier";

    private final InventoryClient inventoryClient;
    private final CatalogClient catalogClient;
    private final SellRepo sellRepo;
    private final SaleReturnRepo saleReturnRepo;
    private final PurchaseRepo purchaseRepo;
    private final VenderRepo venderRepo;
    private final RequestUtil requestUtil;

    /** The report for [from, to]; {@code supplierId} or {@code companyId} narrows it (both null = every supplier). */
    public Map<String, Object> report(LocalDate from, LocalDate to, Long supplierId, Long companyId) {
        if (from == null || to == null || to.isBefore(from)) throw new BusinessRuleException("Choose a valid period.");
        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long org = user.getOrganizationId();

        Map<String, Object> ledger = inventoryClient.getLedgerSummary(from.toString(), to.toString());
        List<Map<String, Object>> ledgerRows = listOf(ledger.get("products"));
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> r : ledgerRows) ids.add(num(r.get("productId")).longValue());

        Map<Long, ProductRef> refs = new HashMap<>();
        if (!ids.isEmpty()) for (ProductRef p : catalogClient.getProducts(ids)) if (p != null) refs.put(p.getId(), p);

        // The product's group: catalog's main supplier, else (bought before it was stamped) its first purchase's supplier.
        Map<Long, Long> supplierOf = new HashMap<>();
        if (!ids.isEmpty()) for (Object[] r : purchaseRepo.firstSupplierByProduct(org, ids)) {
            supplierOf.put(num(r[0]).longValue(), num(r[1]).longValue());
        }
        refs.forEach((id, p) -> { if (p.getMainSupplierId() != null) supplierOf.put(id, p.getMainSupplierId()); });

        Map<Long, String> supplierName = new HashMap<>();
        Set<Long> allowed = null;
        for (Vender v : venderRepo.findScoped(org, user.getUserId())) {
            supplierName.put(v.getId(), v.getName());
            if (supplierId != null && supplierId.equals(v.getId())) { allowed = allowed == null ? new HashSet<>() : allowed; allowed.add(v.getId()); }
            if (companyId != null && v.getCompanies() != null
                    && v.getCompanies().stream().anyMatch(c -> companyId.equals(c.getId()))) {
                allowed = allowed == null ? new HashSet<>() : allowed;
                allowed.add(v.getId());
            }
        }
        if ((supplierId != null || companyId != null) && allowed == null) allowed = Set.of();   // nothing matched

        java.time.LocalDateTime f = from.atStartOfDay(), t = to.plusDays(1).atStartOfDay();
        Map<Long, BigDecimal[]> sales = new HashMap<>();   // [value, bonus]
        for (Object[] r : sellRepo.ssrSalesByProduct(org, f, t)) sales.put(num(r[0]).longValue(), new BigDecimal[]{dec(r[1]), dec(r[2])});
        Map<Long, BigDecimal> returnValue = sums(saleReturnRepo.ssrReturnValueByProduct(org, f, t));
        Map<Long, BigDecimal> returnedOfSales = sums(saleReturnRepo.ssrReturnValueOfSalesInPeriod(org, f, t));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> l : ledgerRows) {
            Long pid = num(l.get("productId")).longValue();
            Long sup = supplierOf.get(pid);
            if (allowed != null && (sup == null || !allowed.contains(sup))) continue;
            BigDecimal[] s = sales.getOrDefault(pid, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            Map<String, Object> row = row(pid, refs.get(pid), l,
                    s[0].add(returnedOfSales.getOrDefault(pid, BigDecimal.ZERO)), s[1],
                    returnValue.getOrDefault(pid, BigDecimal.ZERO));
            row.put("supplierId", sup);
            row.put("supplier", sup == null ? NO_SUPPLIER : supplierName.getOrDefault(sup, "#" + sup));
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("ledgerStart", ledger.get("ledgerStart"));
        out.put("groups", group(rows));
        out.put("netTotal", totals(rows));
        return out;
    }

    // ── the arithmetic — pure, so it is unit-tested against the PDF's own rows ─────────────────────────────────

    static final String[] SUMMED = {"opening", "purchase", "purchaseReturn", "totalStock", "salesQty", "salesValue",
            "salesReturnQty", "salesReturnValue", "netSalesQty", "netSalesValue", "bonusQty", "bonusValue",
            "adjustments", "closingQty", "closingValue"};

    /**
     * One product's row. {@code ledger} = {opening, movements{type: qty}, closing}; {@code salesValue} is GROSS (the
     * returned value of the period's sales already added back); {@code bonus} from the sale lines.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> row(Long pid, ProductRef p, Map<String, Object> ledger, BigDecimal salesValue,
                                   BigDecimal bonus, BigDecimal salesReturnValue) {
        Map<String, BigDecimal> mv = new HashMap<>();
        Object m = ledger.get("movements");
        if (m instanceof Map<?, ?> mm) mm.forEach((k, v) -> mv.put(String.valueOf(k), dec(v)));

        BigDecimal opening = dec(ledger.get("opening")).add(take(mv, StockMovementType.OPENING));   // go-live inside the period
        BigDecimal purchase = take(mv, StockMovementType.PURCHASE).add(take(mv, StockMovementType.PURCHASE_EDIT));
        BigDecimal purchaseReturn = take(mv, StockMovementType.PURCHASE_RETURN).negate();
        BigDecimal goodsOut = take(mv, StockMovementType.SALE).add(take(mv, StockMovementType.SALE_EDIT))
                .add(take(mv, StockMovementType.SALE_VOID)).negate();                     // paid + bonus, net of edits/voids
        BigDecimal salesReturnQty = take(mv, StockMovementType.SALE_RETURN).add(take(mv, StockMovementType.REPOSSESSION));
        BigDecimal adjustments = BigDecimal.ZERO;                                         // everything else, so the row adds up
        for (BigDecimal v : mv.values()) adjustments = adjustments.add(v);
        BigDecimal bonusQty = bonus == null ? BigDecimal.ZERO : bonus;
        BigDecimal salesQty = goodsOut.subtract(bonusQty);
        BigDecimal netSalesQty = salesQty.subtract(salesReturnQty);
        BigDecimal totalStock = opening.add(purchase).subtract(purchaseReturn);
        BigDecimal closing = dec(ledger.get("closing"));
        BigDecimal tp = p == null || p.getLastPurchaseRate() == null ? BigDecimal.ZERO : p.getLastPurchaseRate();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("productId", pid);
        r.put("code", p == null ? null : p.getSku());
        r.put("name", p == null ? "#" + pid : p.getName());
        r.put("pack", p == null ? null : pack(p));
        r.put("tp", money(tp));
        r.put("opening", opening);
        r.put("purchase", purchase);
        r.put("purchaseReturn", purchaseReturn);
        r.put("totalStock", totalStock);
        r.put("salesQty", salesQty);
        r.put("salesValue", money(salesValue));
        r.put("salesReturnQty", salesReturnQty);
        r.put("salesReturnValue", money(salesReturnValue));
        r.put("netSalesQty", netSalesQty);
        r.put("netSalesValue", money(salesValue.subtract(salesReturnValue)));
        r.put("bonusQty", bonusQty);
        r.put("bonusValue", money(bonusQty.multiply(tp)));
        r.put("adjustments", adjustments);
        r.put("closingQty", closing);
        r.put("closingValue", money(closing.multiply(tp)));
        // Closing is the ledger's own figure; this says whether the columns before it reach it (they do unless the sale
        // lines' bonus disagrees with what physically left - shown, never hidden).
        r.put("adds", totalStock.subtract(netSalesQty).subtract(bonusQty).add(adjustments).compareTo(closing) == 0);
        return r;
    }

    static List<Map<String, Object>> group(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> by = new TreeMap<>((a, b) -> {
            if (a.equals(b)) return 0;
            if (NO_SUPPLIER.equals(a)) return 1;    // "No supplier" last
            if (NO_SUPPLIER.equals(b)) return -1;
            return a.compareToIgnoreCase(b) != 0 ? a.compareToIgnoreCase(b) : a.compareTo(b);
        });
        for (Map<String, Object> r : rows) by.computeIfAbsent(String.valueOf(r.get("supplier")), k -> new ArrayList<>()).add(r);
        List<Map<String, Object>> out = new ArrayList<>();
        by.forEach((name, list) -> {
            list.sort(Comparator.comparing(r -> String.valueOf(r.get("name")), String.CASE_INSENSITIVE_ORDER));
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("supplier", name);
            g.put("supplierId", list.get(0).get("supplierId"));
            g.put("rows", list);
            g.put("total", totals(list));
            out.add(g);
        });
        return out;
    }

    static Map<String, Object> totals(List<Map<String, Object>> rows) {
        Map<String, Object> t = new LinkedHashMap<>();
        for (String k : SUMMED) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> r : rows) sum = sum.add(dec(r.get(k)));
            t.put(k, sum);
        }
        return t;
    }

    private static String pack(ProductRef p) {
        Integer n = p.getPackSize();
        return (n == null || n < 1 ? 1 : n) + "'S";
    }

    private static BigDecimal take(Map<String, BigDecimal> mv, String type) {
        BigDecimal v = mv.remove(type);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<Long, BigDecimal> sums(List<Object[]> rows) {
        Map<Long, BigDecimal> m = new HashMap<>();
        for (Object[] r : rows) m.put(num(r[0]).longValue(), dec(r[1]));
        return m;
    }

    static BigDecimal dec(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }

    private static Number num(Object o) {
        return o instanceof Number n ? n : new BigDecimal(String.valueOf(o));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o) {
        if (o instanceof Collection<?> c) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object x : c) if (x instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            return out;
        }
        return List.of();
    }
}
