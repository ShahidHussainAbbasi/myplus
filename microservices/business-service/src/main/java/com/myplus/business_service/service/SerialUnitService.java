package com.myplus.business_service.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.business_service.entity.SerialUnit;
import com.myplus.business_service.repository.SerialUnitRepo;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.settings.Capability;
import com.myplus.common.settings.CapabilityService;
import com.myplus.common.web.exception.ValidationException;

/**
 * SER-2 — the per-unit register: recording which physical units a shop holds.
 *
 * <h3>Two levels, as everywhere else</h3>
 * The tenant capability {@code serialTracking} says whether the shop may record serials at all; the product
 * policy {@code requiresSerial} says whether THIS product must have one. A mobile shop sells handsets that are
 * IMEI-tracked and chargers that are not, so neither level alone can express what the shop does.
 *
 * <h3>Validate before the purchase is written, register after</h3>
 * {@link #validateForPurchase} runs BEFORE anything is saved, so a duplicate IMEI or a miscount costs a refusal
 * rather than a committed purchase that has to be unpicked. {@link #registerForPurchase} then inserts the rows
 * once the purchase has an id to attach them to.
 *
 * <p>The application check exists for the MESSAGE; the database unique index (V52) is what makes it true. Two
 * operators receiving the same handset in the same second both read "not in stock" and both insert — only the
 * index closes that, exactly as {@code V44} argued for installment plans.
 */
@Service
public class SerialUnitService {

    /** IMEIs are 15 digits; other serials vary. 64 matches the column and is generous for both. */
    private static final int MAX_SERIAL_LEN = 64;

    @Autowired private SerialUnitRepo serialUnitRepo;

    /**
     * REQUIRED, never optional. A guard that disables itself when a bean is missing reads as protection while
     * providing none — the OMS O3 lesson recorded in {@code JpaSettingsStore}.
     */
    @Autowired private CapabilityService capabilityService;

    /**
     * Normalise a serial as it is stored and compared: trimmed, upper-cased.
     *
     * <p>Without this, {@code "a1b2"} and {@code "A1B2 "} are two different units to the unique index, and the
     * shop discovers it owns the same handset twice. Case matters because serials are transcribed by hand from
     * a box as often as they are scanned.
     */
    public static String normalise(String serial) {
        return serial == null ? null : serial.trim().toUpperCase();
    }

    /**
     * Split the submitted text into one serial per unit.
     *
     * <p>Arrives as ONE parameter rather than a repeated one because the monolith's purchase proxy keeps only
     * the first value of each parameter ({@code params.put(k, v[0])}) — repeated {@code serials=} would have
     * silently registered a single unit out of ten, with the purchase reporting success.
     *
     * <p>Accepts newlines OR commas: the textarea is line-based, but an operator pasting from a supplier's
     * spreadsheet will produce commas, and refusing that would be a rule with no purpose. Blank lines are
     * dropped rather than counted — trailing newlines are unavoidable in a textarea and must not make the
     * count disagree with the quantity.
     */
    static List<String> split(String serials) {
        List<String> clean = new ArrayList<>();
        if (serials == null) return clean;
        for (String part : serials.split("[\\r\\n,]+")) {
            String n = normalise(part);
            if (n != null && !n.isEmpty()) clean.add(n);
        }
        return clean;
    }

    /**
     * Check the serials a purchase is carrying, BEFORE anything is written.
     *
     * @param serials      what the operator supplied; null or empty means none
     * @param requiresSerial the product's own policy, read from the catalog ref
     * @param quantity     how many units this purchase line brings in
     *
     * @throws ValidationException with an operator-readable message. Every refusal here happens before the
     *         purchase is saved, so nothing needs unpicking.
     */
    public List<String> validateForPurchase(String serials, boolean requiresSerial,
                                            float quantity, Long orgId) {
        List<String> clean = split(serials);

        // Nothing supplied and nothing required: the overwhelmingly common case, and it does no work at all.
        // A shop that does not track serials must not pay for a feature it has not switched on.
        if (clean.isEmpty() && !requiresSerial) return clean;

        // Supplying serials at all is a use of the capability, so the tenant must have it. assertEnabled fails
        // CLOSED — this writes stock records, and "we could not tell" has to mean no.
        capabilityService.assertEnabled(Capability.SERIAL_TRACKING);

        if (requiresSerial && clean.isEmpty()) {
            throw new ValidationException(
                    "This product is tracked by serial number, so each unit received needs one.");
        }

        for (String n : clean) {
            if (n.length() > MAX_SERIAL_LEN) {
                throw new ValidationException("Serial \"" + n + "\" is too long.");
            }
        }

        /*
         * Duplicates WITHIN the same submission. The database index cannot phrase this usefully — it would
         * report a constraint name after the first row was already inserted — and typing the same IMEI twice
         * while working down a box of handsets is the likeliest mistake at this screen.
         */
        Set<String> seen = new LinkedHashSet<>();
        for (String n : clean) {
            if (!seen.add(n)) {
                throw new ValidationException("Serial \"" + n + "\" was entered twice.");
            }
        }

        /*
         * Count must match the quantity received. A purchase of three handsets with two IMEIs means one unit
         * is unaccounted for, and the shop would never find out which — the register would simply be short.
         */
        if (requiresSerial) {
            int expected = Math.round(quantity);
            if (expected > 0 && clean.size() != expected) {
                throw new ValidationException("This purchase is for " + expected + " unit(s) but "
                        + clean.size() + " serial number(s) were entered.");
            }
        }

        // Already on the shelf? Checked for the message; the unique index is what makes it certain.
        for (String n : clean) {
            if (serialUnitRepo.findLive(orgId, n).isPresent()) {
                throw new ValidationException("Serial \"" + n + "\" is already in stock.");
            }
        }
        return clean;
    }

    /**
     * Record the units a purchase brought in. Call AFTER the purchase is saved, with its id.
     *
     * <p>Joins the caller's transaction deliberately: a unit that exists without the purchase that brought it
     * in, or a purchase whose units were silently dropped, are both worse than the whole receipt failing.
     */
    @Transactional
    public int registerForPurchase(Long purchaseId, Long productId, List<String> serials,
                                   String conditionGrade, AuthenticatedUser user) {
        if (serials == null || serials.isEmpty()) return 0;

        String grade = normaliseGrade(conditionGrade);
        LocalDateTime now = LocalDateTime.now();
        List<SerialUnit> rows = new ArrayList<>();
        for (String serial : serials) {
            rows.add(SerialUnit.builder()
                    .organizationId(user.getOrganizationId())
                    .userId(user.getUserId())
                    .storeId(user.getActiveLocationId())
                    .productId(productId)
                    .serialNo(serial)
                    .conditionGrade(grade)
                    .status(SerialUnit.IN_STOCK)
                    .purchaseId(purchaseId)
                    .dated(now)
                    .updated(now)
                    .build());
        }
        serialUnitRepo.saveAll(rows);
        return rows.size();
    }

    // ── SER-3: consuming a unit at the till ─────────────────────────────────────────────────────────

    /**
     * Check the serials a SALE names, before the invoice is written.
     *
     * @return the normalised serials, in the order given
     * @throws ValidationException with an operator-readable reason — every one of these happens before the
     *         sale commits, so the cashier can fix it with the customer still standing there.
     */
    public List<String> validateForSale(String serials, boolean requiresSerial, float quantity,
                                        Long productId, Long orgId) {
        List<String> clean = split(serials);

        // The ordinary line: no serials named, none required. Costs nothing.
        if (clean.isEmpty() && !requiresSerial) return clean;

        capabilityService.assertEnabled(Capability.SERIAL_TRACKING);

        if (requiresSerial && clean.isEmpty()) {
            throw new ValidationException(
                    "This item is tracked by serial number — scan or enter the one being sold.");
        }

        Set<String> seen = new LinkedHashSet<>();
        for (String n : clean) {
            if (!seen.add(n)) {
                throw new ValidationException("Serial \"" + n + "\" was entered twice.");
            }
        }

        if (requiresSerial) {
            int expected = Math.round(quantity);
            if (expected > 0 && clean.size() != expected) {
                throw new ValidationException("This line is for " + expected + " unit(s) but "
                        + clean.size() + " serial number(s) were entered.");
            }
        }

        /*
         * Each named unit must be in stock AND be this product.
         *
         * The product check is not pedantry: selling handset A's IMEI on a line for handset B would mark the
         * wrong unit sold, and the shop would find out when the real one came back under warranty. The serial
         * is the customer's evidence, so it has to point at what they were actually given.
         */
        for (String n : clean) {
            SerialUnit unit = serialUnitRepo.findLive(orgId, n).orElse(null);
            if (unit == null) {
                throw new ValidationException("Serial \"" + n + "\" is not in stock.");
            }
            if (productId != null && !productId.equals(unit.getProductId())) {
                throw new ValidationException("Serial \"" + n + "\" belongs to a different product.");
            }
        }
        return clean;
    }

    /**
     * Mark the named units sold. Call AFTER the invoice exists.
     *
     * <h3>Returns the serials it could NOT claim, rather than throwing</h3>
     * The invoice is already committed by the time this runs — {@code SagaSellService} writes it in its own
     * {@code REQUIRES_NEW} transaction — so throwing here would abandon a sale that has already happened.
     * That is the trap {@code createInstallmentPlan} fell into, and the reason its refusal had to be moved
     * before the write.
     *
     * <p>{@link SerialUnitRepo#markSold} is a compare-and-set on {@code status = 'IN_STOCK'}: exactly one of
     * two tills selling the same handset wins. The loser gets the serial back in this list and the caller
     * reports it on the receipt message — the sale stands, and the discrepancy is visible rather than silent.
     * Validation above catches this in every case except a genuine race.
     */
    @Transactional
    public List<String> consumeForSale(List<String> serials, String invoiceNo, Long orgId) {
        List<String> notClaimed = new ArrayList<>();
        if (serials == null || serials.isEmpty()) return notClaimed;

        LocalDateTime now = LocalDateTime.now();
        for (String serial : serials) {
            if (serialUnitRepo.markSold(orgId, serial, invoiceNo, now) != 1) {
                notClaimed.add(serial);
            }
        }
        return notClaimed;
    }

    /**
     * SER-4 — accept only the grades the register knows, defaulting to NEW.
     *
     * <p>Free text here would produce "used", "Used", "second hand" and "2nd-hand" as four different grades
     * across four operators, and no report could group them afterwards. Unrecognised input becomes NEW rather
     * than being refused: a purchase should not fail over a descriptive field, and the grade stays editable.
     */
    private String normaliseGrade(String grade) {
        if (grade == null) return SerialUnit.NEW;
        String g = grade.trim().toUpperCase();
        if (SerialUnit.USED.equals(g) || SerialUnit.REFURBISHED.equals(g)) return g;
        return SerialUnit.NEW;
    }
}
