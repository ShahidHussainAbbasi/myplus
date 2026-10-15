package com.myplus.pharma.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.pharma.dto.AmendRequest;
import com.myplus.pharma.dto.AmendmentView;
import com.myplus.pharma.dto.PrescriptionDTO;
import com.myplus.pharma.dto.PrescriptionItemDTO;
import com.myplus.pharma.entity.Prescription;
import com.myplus.pharma.entity.PrescriptionAmendment;
import com.myplus.pharma.entity.PrescriptionItem;
import com.myplus.pharma.repository.PrescriptionAmendmentRepository;
import com.myplus.pharma.repository.PrescriptionItemRepository;
import com.myplus.pharma.repository.PrescriptionRepository;

import lombok.RequiredArgsConstructor;

/**
 * HMS H3b (client ruling L-3) — the PHARMACIST changes or cancels a prescription; every change is kept.
 *
 * <p>The client chose that the pharmacist may change the prescription itself, not only the sale. This departs from
 * the NHS EPS model (a signed prescription is immutable), so the safeguard moves to the record: the current lines are
 * what is dispensed, and {@code prescription_amendment} keeps each change — who, when, why, each line before → after —
 * so the doctor's original is never lost and the doctor sees what the pharmacy did.
 *
 * <p>Rules: a reason is required; a fully dispensed, cancelled or expired prescription is not changed; a line never
 * goes below what was already dispensed, and a dispensed line is neither removed nor given another medicine; a
 * medicine added or substituted must be in this pharmacy's catalogue; a screen that has not seen the latest change is
 * refused (never overwrites another pharmacist's change). Who may: {@code rx.dispense} (the controller).
 */
@Service
@RequiredArgsConstructor
public class PrescriptionAmendService {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final int MAX_LINES = 30;

    private final PrescriptionRepository prescriptions;
    private final PrescriptionItemRepository items;
    private final PrescriptionAmendmentRepository amendments;
    private final PrescribedProductCheck catalogue;
    private final PrescriptionService prescriptionService;
    private final StoreScope branches;   // H4

    @Transactional
    public PrescriptionDTO amend(Long id, AmendRequest req, Long orgId, Long userId) {
        Prescription p = scoped(id, orgId, userId);
        assertChangeable(p, "changed");
        String reason = reason(req == null ? null : req.getReason());
        long seen = req.getAmendmentsSeen() == null ? 0 : req.getAmendmentsSeen();
        if (amendments.countByOrganizationIdAndPrescriptionId(orgId, p.getId()) != seen) {
            throw new ValidationException("This prescription was changed on another screen. Reopen it and try again.");
        }
        List<PrescriptionItemDTO> wanted = req.getItems() == null ? List.of() : req.getItems();
        if (wanted.isEmpty()) throw new ValidationException("A prescription keeps at least one medicine. To stop it, cancel it.");
        if (wanted.size() > MAX_LINES) throw new ValidationException("A prescription has at most " + MAX_LINES + " medicines.");

        List<PrescriptionItem> current = items.findByPrescriptionId(p.getId());
        Map<Long, PrescriptionItem> byId = current.stream().collect(Collectors.toMap(PrescriptionItem::getId, Function.identity()));
        Set<Long> productsSeen = new HashSet<>();
        List<Long> toCheck = new ArrayList<>();
        Set<Long> kept = new HashSet<>();
        for (PrescriptionItemDTO w : wanted) {
            String which = name(w.getMedicineName(), w.getProductId());
            if (w.getProductId() == null) throw new ValidationException(which + " has no medicine selected.");
            if (!productsSeen.add(w.getProductId())) throw new ValidationException(which + " is on the prescription twice. Change the quantity instead.");
            if (w.getQuantity() <= 0) throw new ValidationException(which + " needs a quantity greater than zero.");
            if (w.getId() != null) {
                PrescriptionItem old = byId.get(w.getId());
                if (old == null) throw new ValidationException(which + " is not a line of this prescription. Reopen it.");
                kept.add(old.getId());
                if (w.getQuantity() < old.getDispensedQuantity()) {
                    throw new ValidationException(which + ": " + old.getDispensedQuantity() + " already dispensed — the quantity cannot go below that.");
                }
                if (!Objects.equals(old.getProductId(), w.getProductId())) {
                    if (old.getDispensedQuantity() > 0) {
                        throw new ValidationException(name(old.getMedicineName(), old.getProductId())
                                + " was partly dispensed; add the other medicine as a new line instead.");
                    }
                    toCheck.add(w.getProductId());
                }
            } else {
                toCheck.add(w.getProductId());
            }
        }
        for (PrescriptionItem old : current) {
            if (!kept.contains(old.getId()) && old.getDispensedQuantity() > 0) {
                throw new ValidationException(name(old.getMedicineName(), old.getProductId()) + " was partly dispensed; it cannot be removed.");
            }
        }
        if (!toCheck.isEmpty()) {
            PrescriptionDTO probe = new PrescriptionDTO();
            wanted.stream().filter(w -> toCheck.contains(w.getProductId())).forEach(probe.getItems()::add);
            catalogue.assertInCatalogue(probe);   // live, scoped: only this pharmacy's medicines
        }

        // ── the change, line by line ────────────────────────────────────────────────────────────────
        List<Map<String, Object>> changes = new ArrayList<>();
        for (PrescriptionItem old : current) {
            if (!kept.contains(old.getId())) {
                changes.add(change(old.getMedicineName(), snapshot(old), null));
                items.delete(old);
            }
        }
        for (PrescriptionItemDTO w : wanted) {
            if (w.getId() != null) {
                PrescriptionItem old = byId.get(w.getId());
                Map<String, Object> before = snapshot(old);
                old.setProductId(w.getProductId());
                old.setMedicineName(trim(w.getMedicineName(), 200));
                old.setQuantity(w.getQuantity());
                old.setDosage(trim(w.getDosage(), 100));
                old.setFrequency(trim(w.getFrequency(), 100));
                old.setDuration(trim(w.getDuration(), 100));
                Map<String, Object> after = snapshot(old);
                if (!before.equals(after)) changes.add(change(old.getMedicineName(), before, after));
                items.save(old);
            } else {
                PrescriptionItem n = items.save(PrescriptionItem.builder().prescription(p).productId(w.getProductId())
                        .medicineName(trim(w.getMedicineName(), 200)).quantity(w.getQuantity()).dosage(trim(w.getDosage(), 100))
                        .frequency(trim(w.getFrequency(), 100)).duration(trim(w.getDuration(), 100)).dispensedQuantity(0).build());
                changes.add(change(n.getMedicineName(), null, snapshot(n)));
            }
        }
        if (changes.isEmpty()) throw new ValidationException("Nothing was changed.");
        items.flush();
        p.setStatus(DispenseService.recomputeStatus(items.findByPrescriptionId(p.getId())));
        prescriptions.save(p);
        record(orgId, p.getId(), PrescriptionAmendment.AMEND, reason, changes, userId);
        return prescriptionService.get(p.getId(), orgId, userId);
    }

    /**
     * Cancel with a reason. A doctor's prescription REQUIRES one (the doctor sees it); a counter prescription keeps
     * today's behaviour (a reason when given). Anything already dispensed stays recorded.
     */
    @Transactional
    public PrescriptionDTO cancel(Long id, String rawReason, Long orgId, Long userId) {
        Prescription p = scoped(id, orgId, userId);
        if (p.getStatus() == Prescription.Status.CANCELLED) return prescriptionService.get(p.getId(), orgId, userId);
        boolean fromDoctor = Prescription.SOURCE_DOCTOR.equals(p.getSource());
        String reason = rawReason == null || rawReason.isBlank() ? null : rawReason.trim();
        if (fromDoctor && reason == null) throw new ValidationException("Say why the doctor's prescription is cancelled — the doctor sees it.");
        PrescriptionDTO out = prescriptionService.cancel(id, orgId, userId);
        if (reason != null) record(orgId, p.getId(), PrescriptionAmendment.CANCEL, reason(reason), List.of(), userId);
        return out;
    }

    @Transactional(readOnly = true)
    public List<AmendmentView> history(Long id, Long orgId, Long userId) {
        Prescription p = scoped(id, orgId, userId);
        return amendments.findByOrganizationIdAndPrescriptionIdOrderByIdAsc(orgId, p.getId()).stream().map(a -> {
            List<Map<String, Object>> ch;
            try {
                ch = a.getChanges() == null ? List.of() : JSON.readValue(a.getChanges(), new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception e) { ch = List.of(); }
            return AmendmentView.builder().id(a.getId()).kind(a.getKind()).reason(a.getReason()).amendedBy(a.getAmendedBy())
                    .amendedAt(a.getAmendedAt()).changes(ch).build();
        }).toList();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────

    private Prescription scoped(Long id, Long orgId, Long userId) {
        return prescriptions.findByIdScoped(id, orgId, userId).filter(x -> branches.canAccess(x.getStoreId())).orElseThrow(() -> new ResourceNotFoundException("Prescription not found"));
    }

    private static void assertChangeable(Prescription p, String verb) {
        if (p.getStatus() == Prescription.Status.CANCELLED) throw new ValidationException("A cancelled prescription cannot be " + verb + ".");
        if (p.getStatus() == Prescription.Status.FULLY_DISPENSED) throw new ValidationException("A fully dispensed prescription cannot be " + verb + ".");
        if (p.getValidUntil() != null && p.getValidUntil().isBefore(com.myplus.common.security.time.TenantClock.today())) {
            throw new ValidationException("An expired prescription cannot be " + verb + ".");
        }
    }

    static String reason(String raw) {
        String r = raw == null ? "" : raw.trim();
        if (r.isEmpty()) throw new ValidationException("Say why the prescription is changed, e.g. strength not in stock.");
        if (r.length() > 500) throw new ValidationException("The reason is at most 500 characters.");
        return r;
    }

    private void record(Long orgId, Long rxId, String kind, String reason, List<Map<String, Object>> changes, Long userId) {
        String json;
        try { json = changes.isEmpty() ? null : JSON.writeValueAsString(changes); }
        catch (Exception e) { throw new IllegalStateException("could not record the change", e); }
        if (json != null && json.length() > 8000) throw new ValidationException("Too many changes at once. Save them in two steps.");
        amendments.save(PrescriptionAmendment.builder().organizationId(orgId).prescriptionId(rxId).kind(kind).reason(reason)
                .changes(json).amendedBy(userId).amendedAt(LocalDateTime.now()).build());
    }

    private static Map<String, Object> snapshot(PrescriptionItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", i.getProductId());
        m.put("medicine", i.getMedicineName());
        m.put("qty", i.getQuantity());
        m.put("dose", i.getDosage());
        m.put("freq", i.getFrequency());
        m.put("days", i.getDuration());
        return m;
    }

    private static Map<String, Object> change(String medicine, Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("medicine", medicine);
        m.put("before", before);
        m.put("after", after);
        return m;
    }

    private static String name(String medicine, Long productId) {
        return medicine == null || medicine.isBlank() ? "Product #" + productId : "'" + medicine.trim() + "'";
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
