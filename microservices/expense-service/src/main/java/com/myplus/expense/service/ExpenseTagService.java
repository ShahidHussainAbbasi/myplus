package com.myplus.expense.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.myplus.commerce.contracts.client.ExpenseTagClient;
import com.myplus.commerce.contracts.dto.ExpenseTagView;
import com.myplus.common.web.exception.ValidationException;

/**
 * EX-2b — what an expense line may be tagged to, confirmed by the module that owns it.
 *
 * <h3>A registry, not a switch on verticals</h3>
 * Two maps say everything: which module answers for a SOURCE (the dashboard asking for options) and which module
 * owns a tag TYPE (when a saved line names one). Adding a vertical is one endpoint in its service and one line in
 * each map — never an {@code if (education)} in the voucher code.
 *
 * <h3>Never store what the module did not confirm</h3>
 * {@link #confirm} re-reads the owning module's list WITH THE CALLER'S IDENTITY and accepts the id only if it is
 * there. The browser's label is ignored; the module's label is snapshotted. A foreign or out-of-branch id is
 * refused. If the module cannot be reached the save is refused too — an unconfirmed tag is worse than none.
 */
@Service
public class ExpenseTagService {

    private static final Logger LOG = LoggerFactory.getLogger(ExpenseTagService.class);

    private final Map<String, ExpenseTagClient> bySource;
    private final Map<String, String> sourceOfType = Map.of(
            "SCHOOL", "education",
            "VEHICLE", "education",
            "LAND", "agriculture");

    /**
     * FP-3 — business-service answers the same SPI with the caller's SUPPLIERS. Deliberately NOT in {@link #bySource}:
     * a supplier is who a BILL is owed to (one per voucher), never a per-line tag, so the line picker never offers one
     * and {@link #confirm} refuses the type.
     */
    private final ExpenseTagClient business;

    public static final String SUPPLIER = "SUPPLIER";

    public ExpenseTagService(@Qualifier("educationTags") ExpenseTagClient education,
                             @Qualifier("agricultureTags") ExpenseTagClient agriculture,
                             @Qualifier("businessTags") ExpenseTagClient business) {
        this.bySource = Map.of("education", education, "agriculture", agriculture);
        this.business = business;
    }

    /** FP-3 — the suppliers this caller may raise a bill against (business-service's own scoping applies). */
    public List<ExpenseTagView> suppliers() {
        try {
            List<ExpenseTagView> list = business.tags();
            if (list == null) return List.of();
            return list.stream().filter(v -> v != null && SUPPLIER.equals(v.getType())).toList();
        } catch (Exception e) {
            LOG.warn("suppliers unavailable from business-service", e);
            throw new ValidationException("Could not load your suppliers. Try again in a moment.");
        }
    }

    /**
     * FP-3 — the supplier's name if business-service lists this id for the caller, else a refusal. A foreign or
     * invented id never reaches a bill (the same rule as {@link #confirm}).
     */
    public String confirmSupplier(Long id) {
        if (id == null) throw new ValidationException("Choose the supplier this bill is owed to.");
        for (ExpenseTagView v : suppliers()) {
            if (id.equals(v.getId())) return v.getLabel();
        }
        throw new ValidationException("That supplier was not found, or is not one of yours.");
    }

    /** The options a dashboard offers. An unknown source offers nothing (the shop has no tags yet). */
    public List<ExpenseTagView> options(String source) {
        ExpenseTagClient c = source == null ? null : bySource.get(source.trim().toLowerCase(Locale.ROOT));
        if (c == null) return List.of();
        try {
            List<ExpenseTagView> list = c.tags();
            return list == null ? List.of() : list;
        } catch (Exception e) {
            LOG.warn("expense tags unavailable from {}", source, e);
            throw new ValidationException("Could not load what expenses can be tagged to. Try again in a moment.");
        }
    }

    /** The module's label for this tag, or a refusal that says what was wrong. Both null = an untagged line. */
    public String confirm(String type, Long id) {
        return confirm(type, id, new java.util.HashMap<>());
    }

    /**
     * As {@link #confirm(String, Long)}, reading each module's list at most ONCE per save: {@code seen} carries the
     * lists already fetched, so a 50-line voucher costs one call per module, not fifty (performance standard).
     */
    public String confirm(String type, Long id, Map<String, List<ExpenseTagView>> seen) {
        if ((type == null || type.isBlank()) && id == null) return null;
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        String source = sourceOfType.get(t);
        if (source == null) throw new ValidationException("An expense cannot be tagged to \"" + type + "\".");
        if (id == null) throw new ValidationException("Choose what this expense was for.");
        for (ExpenseTagView v : seen.computeIfAbsent(source, this::options)) {
            if (t.equals(v.getType()) && id.equals(v.getId())) return v.getLabel();
        }
        throw new ValidationException("That " + t.toLowerCase(Locale.ROOT) + " was not found, or is not one of yours.");
    }

    /** Normalised type for storage (upper case), or null for an untagged line. */
    public static String normaliseType(String type) {
        return type == null || type.isBlank() ? null : type.trim().toUpperCase(Locale.ROOT);
    }
}
