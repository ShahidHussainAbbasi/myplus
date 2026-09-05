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

    /**
     * SER-2 (read) — the units several purchases brought in, keyed by purchase.
     *
     * <p>One query for the whole page. The purchase register is the screen a shop lives on, and the list
     * endpoint already batches its product and vendor lookups for exactly this reason.
     */
    public java.util.Map<Long, java.util.List<SerialUnit>> unitsByPurchase(
            Long orgId, java.util.Collection<Long> purchaseIds) {
        java.util.Map<Long, java.util.List<SerialUnit>> byPurchase = new java.util.LinkedHashMap<>();
        if (orgId == null || purchaseIds == null || purchaseIds.isEmpty()) return byPurchase;
        for (SerialUnit u : serialUnitRepo.findByPurchaseIds(orgId, purchaseIds)) {
            byPurchase.computeIfAbsent(u.getPurchaseId(), k -> new ArrayList<>()).add(u);
        }
        return byPurchase;
    }

    /**
     * The serials of a bill as ONE field, in the shape the purchase form posts them back in.
     *
     * <p>Comma-separated because {@link #split} accepts commas and newlines alike, so what the grid shows,
     * what the edit form loads and what the browser submits are the same string. A different separator on the
     * way out than on the way in is how a round-trip quietly stops being one.
     */
    public static String join(java.util.List<SerialUnit> units) {
        if (units == null || units.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (SerialUnit u : units) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(u.getSerialNo());
        }
        return sb.toString();
    }

    /**
     * SER-2 (edit) — make the register agree with an EDITED purchase line.
     *
     * <h3>Why an edit needed its own method rather than reusing the add path</h3>
     * {@link #registerForPurchase} only ever inserts. An edit can also REMOVE a unit — an IMEI typed wrong
     * and corrected — and it must not re-insert the ones that were already right, because a second live row
     * for the same serial is precisely what V52's unique index refuses. So the operation is a reconcile:
     * keep, add, remove.
     *
     * <h3>A unit that has left the shop cannot be un-received</h3>
     * Removing a SOLD unit would erase the only record connecting a customer to the handset they are holding
     * — the warranty claim, the return and the police enquiry all start there. Refused with the serial
     * named, rather than silently skipped: an operator correcting a bill whose goods have since been sold
     * needs to be told, not left with a register that quietly disagrees with the form they just saved.
     *
     * <h3>Validates everything before it changes anything</h3>
     * Same placement and the same reason as {@link #validateForPurchase}: this runs inside the edit's
     * transaction and before the record is written, so a refusal costs nothing and leaves nothing to unpick.
     *
     * @param serials what the operator submitted — the FULL list for this line, never a delta
     * @return how many units the register holds for this purchase afterwards
     */
    @Transactional
    public int reconcileForPurchase(Long purchaseId, Long productId, String serials, boolean requiresSerial,
                                    float quantity, String conditionGrade, AuthenticatedUser user) {
        Long orgId = user.getOrganizationId();
        List<String> submitted = split(serials);
        List<SerialUnit> existing = serialUnitRepo.findByPurchase(orgId, purchaseId);

        // Nothing held and nothing asked for: the ordinary edit of an ordinary product, and it does no work.
        if (submitted.isEmpty() && existing.isEmpty() && !requiresSerial) return 0;

        // Naming serials at all is a use of the capability. Fails CLOSED, exactly as the add path does.
        capabilityService.assertEnabled(Capability.SERIAL_TRACKING);

        if (requiresSerial && submitted.isEmpty()) {
            throw new ValidationException(
                    "This product is tracked by serial number, so each unit received needs one.");
        }

        for (String n : submitted) {
            if (n.length() > MAX_SERIAL_LEN) {
                throw new ValidationException("Serial \"" + n + "\" is too long.");
            }
        }

        Set<String> wanted = new LinkedHashSet<>();
        for (String n : submitted) {
            if (!wanted.add(n)) {
                throw new ValidationException("Serial \"" + n + "\" was entered twice.");
            }
        }

        if (requiresSerial) {
            int expected = Math.round(quantity);
            if (expected > 0 && wanted.size() != expected) {
                throw new ValidationException("This purchase is for " + expected + " unit(s) but "
                        + wanted.size() + " serial number(s) were entered.");
            }
        }

        // What this bill already holds, and what of it the edit is dropping.
        Set<String> held = new LinkedHashSet<>();
        List<SerialUnit> removing = new ArrayList<>();
        for (SerialUnit u : existing) {
            held.add(u.getSerialNo());
            if (!wanted.contains(u.getSerialNo())) removing.add(u);
        }
        for (SerialUnit u : removing) {
            if (!SerialUnit.IN_STOCK.equals(u.getStatus())) {
                throw new ValidationException("Serial \"" + u.getSerialNo() + "\" has already left"
                        + " the shop" + (u.getInvoiceNo() != null ? " on invoice " + u.getInvoiceNo() : "")
                        + ", so it cannot be removed from this bill. Record a sale return first.");
            }
        }

        // A serial this bill is taking ON must not already be live somewhere else. Checked for the MESSAGE;
        // the unique index is what makes it certain.
        for (String n : wanted) {
            if (held.contains(n)) continue;                       // already ours — not a new claim
            if (serialUnitRepo.findLive(orgId, n).isPresent()) {
                throw new ValidationException("Serial \"" + n + "\" is already in stock.");
            }
        }

        // ── Everything above refused without writing. From here the register changes. ──
        if (!removing.isEmpty()) serialUnitRepo.deleteAll(removing);

        String grade = normaliseGrade(conditionGrade);
        LocalDateTime now = LocalDateTime.now();
        List<SerialUnit> adding = new ArrayList<>();
        for (String n : wanted) {
            if (held.contains(n)) continue;
            adding.add(SerialUnit.builder()
                    .organizationId(orgId)
                    .userId(user.getUserId())
                    .storeId(user.getActiveLocationId())
                    .productId(productId)
                    .serialNo(n)
                    .conditionGrade(grade)
                    .status(SerialUnit.IN_STOCK)
                    .purchaseId(purchaseId)
                    .dated(now)
                    .updated(now)
                    .build());
        }
        if (!adding.isEmpty()) serialUnitRepo.saveAll(adding);

        /*
         * The grade is a property of the DELIVERY, so an edit that regrades the line regrades every unit it
         * brought in — including the ones already there. Without this, correcting "New" to "Used" would
         * apply only to units added by the same edit, and one bill would hold two grades for goods that
         * arrived in one box.
         */
        for (SerialUnit u : existing) {
            if (!wanted.contains(u.getSerialNo())) continue;      // being removed
            if (!grade.equals(u.getConditionGrade())) {
                u.setConditionGrade(grade);
                u.setUpdated(now);
                serialUnitRepo.save(u);
            }
        }
        return wanted.size();
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

    // ── SER-3 (fix): putting a unit BACK ───────────────────────────────────────────

    /**
     * The units a given invoice took off the shelf and has not given back.
     *
     * <p>Filtered in memory rather than with another query: this is the units of ONE invoice, a handful of
     * rows, and {@link SerialUnitRepo#findBySaleInvoice} is already the query the lookup path needs.
     */
    private List<SerialUnit> soldOn(Long orgId, String invoiceNo, Long productId) {
        List<SerialUnit> out = new ArrayList<>();
        if (orgId == null || invoiceNo == null || invoiceNo.isEmpty()) return out;
        for (SerialUnit u : serialUnitRepo.findBySaleInvoice(orgId, invoiceNo)) {
            if (!SerialUnit.SOLD.equals(u.getStatus())) continue;
            if (productId != null && !productId.equals(u.getProductId())) continue;
            out.add(u);
        }
        return out;
    }

    /**
     * SER-3 (fix) — a SALE RETURN puts the handset back on the shelf.
     *
     * <h3>The gap this closes</h3>
     * {@link SerialUnitRepo#markReturned} was written with the return path in mind and then never called by
     * anything — a fact a caller count makes plain and a reading of the code does not. So a returned handset
     * stayed SOLD for ever: it could not be sold again (the sale path refuses a unit that is not in stock),
     * and the shop had a phone on the shelf that its own register said belonged to a customer.
     *
     * <h3>Which unit came back is a question, not an inference</h3>
     * Returning one of three handsets returns a SPECIFIC handset, and guessing would mark the wrong customer's
     * unit as back in stock. So:
     * <ul>
     *   <li>serials named — those units, each checked against this invoice and this product;</li>
     *   <li>none named and the WHOLE line coming back — unambiguous, so all of them;</li>
     *   <li>none named and a PARTIAL return — refused, naming what is needed.</li>
     * </ul>
     * The refusal is deliberate. A partial return that silently restocked the first unit it found would put
     * the wrong IMEI back on sale, and nobody would learn of it until a warranty claim.
     *
     * <h3>Inert for everything else</h3>
     * A line whose invoice put no units in the register does nothing at all — which is every sale of every
     * product that is not serial-tracked, and every legacy sale from before the register existed.
     *
     * @return the serials actually put back
     */
    @Transactional
    public List<String> restoreForReturn(Long orgId, String invoiceNo, Long productId,
                                         String serials, float returnQty) {
        List<String> restored = new ArrayList<>();
        List<SerialUnit> sold = soldOn(orgId, invoiceNo, productId);
        if (sold.isEmpty()) return restored;                  // not a tracked line — nothing to put back

        List<String> named = split(serials);
        List<SerialUnit> coming;

        if (!named.isEmpty()) {
            Set<String> seen = new LinkedHashSet<>();
            coming = new ArrayList<>();
            for (String n : named) {
                if (!seen.add(n)) {
                    throw new ValidationException("Serial " + q(n) + " was entered twice.");
                }
                SerialUnit match = null;
                for (SerialUnit u : sold) {
                    if (u.getSerialNo().equals(n)) { match = u; break; }
                }
                if (match == null) {
                    throw new ValidationException("Serial " + q(n) + " was not sold on invoice "
                            + invoiceNo + ", so it cannot be returned against it.");
                }
                coming.add(match);
            }
            int expected = Math.round(returnQty);
            if (expected > 0 && coming.size() != expected) {
                throw new ValidationException("This return is for " + expected + " unit(s) but "
                        + coming.size() + " serial number(s) were entered.");
            }
        } else if (Math.round(returnQty) == sold.size()) {
            coming = sold;                                    // the whole line — no ambiguity to resolve
        } else {
            throw new ValidationException("This invoice sold " + sold.size()
                    + " serial-tracked unit(s). Enter the serial number(s) being returned so the right unit"
                    + " goes back on the shelf.");
        }

        LocalDateTime now = LocalDateTime.now();
        for (SerialUnit u : coming) {
            if (serialUnitRepo.markReturned(orgId, u.getSerialNo(), now) == 1) restored.add(u.getSerialNo());
        }
        return restored;
    }

    /**
     * SER-3 (fix) — a VOIDED invoice never happened, so every unit it named goes back.
     *
     * <p>No serials to ask for and no ambiguity to resolve: a void reverses the WHOLE document, so the answer
     * is all of them. That is why this is separate from {@link #restoreForReturn} rather than a special case
     * of it — the two look alike and are asked different questions.
     *
     * <p>Never throws. A void is a books-safe reversal that has already reversed stock and the ledger by the
     * time this runs; failing it here would leave an invoice that is void everywhere except the register.
     *
     * @return the serials put back
     */
    @Transactional
    public List<String> restoreForVoid(Long orgId, String invoiceNo) {
        List<String> restored = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (SerialUnit u : soldOn(orgId, invoiceNo, null)) {
            try {
                if (serialUnitRepo.markReturned(orgId, u.getSerialNo(), now) == 1) restored.add(u.getSerialNo());
            } catch (RuntimeException ex) {
                // Logged by the caller against the invoice; one stubborn unit must not abort the rest.
            }
        }
        return restored;
    }

    /** Quote a serial in a message. One helper so every refusal reads the same way. */
    private static String q(String serial) {
        return "\"" + serial + "\"";
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
