package com.myplus.clinical.service;

import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.myplus.clinical.config.PharmaRxClient;

import com.myplus.clinical.dto.ConsultDtos.EncounterUpdate;
import com.myplus.clinical.dto.ConsultDtos.EncounterView;
import com.myplus.clinical.dto.ConsultDtos.Identity;
import com.myplus.clinical.dto.ConsultDtos.NoteRequest;
import com.myplus.clinical.dto.ConsultDtos.NoteView;
import com.myplus.clinical.dto.ConsultDtos.RxLine;
import com.myplus.clinical.dto.ConsultDtos.RxRequest;
import com.myplus.clinical.entity.ClinicalNote;
import com.myplus.clinical.entity.Encounter;
import com.myplus.clinical.entity.EncounterRxItem;
import com.myplus.clinical.entity.Patient;
import com.myplus.clinical.entity.QueueStatus;
import com.myplus.clinical.entity.QueueToken;
import com.myplus.clinical.repository.ClinicalNoteRepo;
import com.myplus.clinical.repository.EncounterRepo;
import com.myplus.clinical.repository.EncounterRxItemRepo;
import com.myplus.clinical.repository.PatientRepo;
import com.myplus.clinical.repository.QueueTokenRepo;
import com.myplus.common.security.time.TenantClock;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * HMS S3a — the doctor's consultation (design §4c). Every method requires the clinic to be on AND
 * {@code clinic.consult}: the clinical record is the doctor's, never the front desk's (M-15).
 *
 * <ul>
 *   <li><b>start</b> — the token goes CALLED → IN_CONSULTATION (the one conditional UPDATE) and the encounter is made;
 *       a second Start of the same token returns the SAME encounter (uq_encounter_token).</li>
 *   <li><b>open</b> — the identity banner (name + MRN + date of birth, before anything clinical), this visit and the
 *       earlier ones with their notes. Recorded as a view of the patient.</li>
 *   <li><b>update</b> — complaint and vitals, range-checked, only while the visit is OPEN, with the version the
 *       screen loaded (a stale edit is refused, never overwrites).</li>
 *   <li><b>note</b> — append-only; a correction is a new note pointing at the one it corrects.</li>
 *   <li><b>complete</b> — token and encounter close together, in one transaction.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ConsultService {

    private final EncounterRepo encounters;
    private final ClinicalNoteRepo notes;
    private final QueueTokenRepo tokens;
    private final PatientRepo patients;
    private final QueueService queue;
    private final ClinicAccess access;
    private final ClinicAuditService audit;
    private final EncounterRxItemRepo rxItems;
    private final PharmaRxClient pharma;
    private final com.myplus.clinical.config.LinkRouteClient routes;   // HC-1d R1

    private Long guard() {
        access.assertModuleOn();
        access.assertCanConsult();
        return access.org();
    }

    /** Start (or reopen) the consultation for a token: CALLED → IN_CONSULTATION, and its one encounter. */
    @Transactional
    public EncounterView start(Long tokenId) {
        Long org = guard();
        QueueToken t = tokens.findByIdAndOrganizationId(tokenId, org)
                .orElseThrow(() -> new ResourceNotFoundException("Token not found."));
        access.assertMayWorkProvider(t.getProviderId(), t.getProviderName());   // H2: own queue only, when linked
        if (QueueStatus.CALLED.equals(t.getStatus())) {
            queue.move(tokenId, "start", null);
        } else if (QueueStatus.PARKED.equals(t.getStatus())) {
            queue.move(tokenId, "resume", null);   // S3b-1: opening a parked visit brings the patient back in
        } else if (!QueueStatus.IN_CONSULTATION.equals(t.getStatus()) && !QueueStatus.PARKED.equals(t.getStatus())) {
            throw new ValidationException(t.getTokenLabel() + " is " + QueueStatus.words(t.getStatus())
                    + " — call the patient first.");
        }
        Encounter e = encounters.findByOrganizationIdAndTokenId(org, tokenId).orElseGet(() -> {
            Encounter n = new Encounter();
            n.setOrganizationId(org);
            n.setTokenId(t.getId());
            n.setPatientId(t.getPatientId());
            n.setProviderId(t.getProviderId());
            n.setDoctorUserId(access.userId());
            LocalDateTime now = LocalDateTime.now();
            n.setStartedAt(now);
            n.setCreatedAt(now);
            n.setUpdatedAt(now);
            try {
                return encounters.saveAndFlush(n);
            } catch (DataIntegrityViolationException raced) {
                // a second Start at the same moment: the first one's encounter is the answer
                return encounters.findByOrganizationIdAndTokenId(org, tokenId).orElseThrow(() -> raced);
            }
        });
        return open(e.getId());
    }

    /**
     * The consultation screen: who, this visit, and the earlier visits. A clinical read — recorded, so NOT readOnly:
     * the audit row is written in this transaction (a readOnly one refused it: "Connection is read-only", S3a gate D-02).
     */
    @Transactional
    public EncounterView open(Long encounterId) {
        Long org = guard();
        Encounter e = scoped(encounterId, org);
        Patient p = patients.findByIdAndOrganizationId(e.getPatientId(), org)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found."));
        QueueToken t = tokens.findByIdAndOrganizationId(e.getTokenId(), org).orElse(null);
        List<Encounter> earlier = encounters.history(org, p.getId(), e.getId(), PageRequest.of(0, 20));
        List<Long> ids = new ArrayList<>();
        ids.add(e.getId());
        earlier.forEach(x -> ids.add(x.getId()));
        Map<Long, List<NoteView>> byEncounter = notes.forEncounters(org, ids).stream()
                .collect(Collectors.groupingBy(ClinicalNote::getEncounterId,
                        Collectors.mapping(ConsultService::noteView, Collectors.toList())));
        Map<Long, QueueToken> earlierTokens = tokens.findAllById(earlier.stream().map(Encounter::getTokenId).toList())
                .stream().filter(x -> org.equals(x.getOrganizationId()))
                .collect(Collectors.toMap(QueueToken::getId, x -> x));

        EncounterView v = view(e, t, byEncounter);
        v.setPatient(identity(p));
        v.setRxLines(rxItems.findByOrganizationIdAndEncounterIdOrderByLineNo(org, e.getId()).stream()
                .map(ConsultService::rxLine).toList());
        v.setRxId(e.getRxId());
        v.setRxSubmittedAt(e.getRxSubmittedAt());
        v.setHistory(earlier.stream().map(x -> view(x, earlierTokens.get(x.getTokenId()), byEncounter)).toList());
        audit.patient("PATIENT_CLINICAL_VIEW", p.getMrn(), "consultation opened by the doctor");
        return v;
    }

    @Transactional
    public EncounterView update(Long encounterId, EncounterUpdate req) {
        Long org = guard();
        Encounter e = scoped(encounterId, org);
        if (!Encounter.OPEN.equals(e.getStatus())) {
            throw new ValidationException("This visit is completed. Add a note to correct it.");
        }
        if (req.getVersion() != null && !Objects.equals(req.getVersion(), e.getVersion())) {
            throw new ValidationException("This visit was changed on another screen. Reopen it and try again.");
        }
        int[] bp = EncounterRules.bloodPressure(req.getBloodPressure());
        e.setChiefComplaint(EncounterRules.complaint(req.getChiefComplaint()));
        e.setBpSystolic(bp == null ? null : bp[0]);
        e.setBpDiastolic(bp == null ? null : bp[1]);
        e.setPulse(EncounterRules.pulse(req.getPulse()));
        e.setTemperatureF(EncounterRules.temperatureF(req.getTemperatureF()));
        e.setSpo2(EncounterRules.spo2(req.getSpo2()));
        e.setWeightKg(EncounterRules.weightKg(req.getWeightKg()));
        e.setHeightCm(EncounterRules.heightCm(req.getHeightCm()));
        e.setUpdatedAt(LocalDateTime.now());
        encounters.saveAndFlush(e);
        audit.patient("ENCOUNTER_UPDATE", mrnOf(e, org), "complaint and vitals");
        return open(encounterId);
    }

    /** Append-only. Allowed after completion too — that is how a completed visit is corrected. */
    @Transactional
    public EncounterView note(Long encounterId, NoteRequest req) {
        Long org = guard();
        Encounter e = scoped(encounterId, org);
        if (req.getAmendsNoteId() != null) {
            ClinicalNote amended = notes.findById(req.getAmendsNoteId())
                    .filter(n -> org.equals(n.getOrganizationId()) && e.getId().equals(n.getEncounterId()))
                    .orElseThrow(() -> new ResourceNotFoundException("The note being corrected was not found on this visit."));
            req.setAmendsNoteId(amended.getId());
        }
        ClinicalNote n = new ClinicalNote();
        n.setOrganizationId(org);
        n.setEncounterId(e.getId());
        n.setPatientId(e.getPatientId());
        n.setAuthorUserId(access.userId());
        n.setBody(EncounterRules.note(req.getBody()));
        n.setAmendsNoteId(req.getAmendsNoteId());
        n.setCreatedAt(LocalDateTime.now());
        notes.saveAndFlush(n);
        audit.patient("CLINICAL_NOTE_ADD", mrnOf(e, org), req.getAmendsNoteId() == null ? "note" : "correction of note " + req.getAmendsNoteId());
        return open(encounterId);
    }

    /** Close the visit: the token (IN_CONSULTATION → COMPLETED) and the encounter, together. */
    @Transactional
    public EncounterView complete(Long encounterId) {
        Long org = guard();
        Encounter e = scoped(encounterId, org);
        if (Encounter.COMPLETED.equals(e.getStatus())) return open(encounterId);
        queue.move(e.getTokenId(), "complete", null);
        e.setStatus(Encounter.COMPLETED);
        LocalDateTime now = LocalDateTime.now();
        e.setCompletedAt(now);
        e.setUpdatedAt(now);
        encounters.saveAndFlush(e);
        return open(encounterId);
    }

    /**
     * HMS S3b-1 — the doctor's prescription, saved as a whole (the screen sends every line it shows). Refused once
     * submitted (07c): the pharmacy may already be dispensing it; a change after that is a new visit's prescription.
     */
    @Transactional
    public EncounterView saveRx(Long encounterId, RxRequest req) {
        Long org = guard();
        Encounter e = scoped(encounterId, org);
        if (e.getRxId() != null) {
            throw new ValidationException("This prescription is with the pharmacy already. It cannot be changed here.");
        }
        List<RxLine> lines = EncounterRules.rxLines(req == null ? null : req.getLines());
        rxItems.deleteForEncounter(org, e.getId());
        int n = 0;
        for (RxLine l : lines) {
            EncounterRxItem i = new EncounterRxItem();
            i.setOrganizationId(org);
            i.setEncounterId(e.getId());
            i.setLineNo(++n);
            i.setProductId(l.getProductId());
            i.setMedicineName(l.getMedicineName());
            i.setQuantity(Integer.valueOf(l.getQuantity()));
            i.setDosage(l.getDosage());
            i.setFrequency(l.getFrequency());
            i.setDuration(l.getDuration());
            rxItems.save(i);
        }
        rxItems.flush();
        audit.patient("RX_DRAFT_SAVE", mrnOf(e, org), lines.size() + " medicine(s)");
        return open(encounterId);
    }

    /**
     * HMS S3b-1 — send the prescription to the pharmacy. Idempotent end to end: a second Submit of a submitted visit
     * returns it as it is, and pharma-service answers a repeated {@code enc-<id>} with the SAME prescription — so a
     * lost reply or a double click never makes two scripts to dispense.
     */
    @Transactional
    public EncounterView submitRx(Long encounterId) {
        Long org = guard();
        Encounter e = scoped(encounterId, org);
        if (e.getRxId() != null) return open(encounterId);
        List<EncounterRxItem> lines = rxItems.findByOrganizationIdAndEncounterIdOrderByLineNo(org, e.getId());
        if (lines.isEmpty()) throw new ValidationException("Add at least one medicine, then Submit.");
        Patient p = patients.findByIdAndOrganizationId(e.getPatientId(), org)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found."));
        QueueToken t = tokens.findByIdAndOrganizationId(e.getTokenId(), org).orElse(null);

        Route route = route();
        if (route.none()) throw noLink();
        if (route.linked()) return submitLinked(e, p, t, lines, route);

        PharmaRxClient.Rx rx = PharmaRxClient.Rx.builder()
                .patientName(p.getName()).patientPhone(p.getPhone()).partyId(p.getPartyId())
                .doctorName(prescriber(t))
                .diagnosis(e.getChiefComplaint())
                .notes(p.getMrn())
                .tokenLabel(t == null ? null : t.getTokenLabel())
                .encounterId(e.getId())
                .externalRef("enc-" + e.getId())
                .storeId(routeOf(t))   // H4: the prescribing doctor's pharmacy branch (null = every branch)
                .items(lines.stream().map(l -> PharmaRxClient.Item.builder().productId(l.getProductId())
                        .medicineName(l.getMedicineName()).quantity(l.getQuantity()).dosage(l.getDosage())
                        .frequency(l.getFrequency()).duration(l.getDuration()).build()).toList())
                .build();
        PharmaRxClient.Envelope answer;
        try {
            answer = pharma.create(rx);
        } catch (RestClientResponseException refused) {
            throw new ValidationException("The pharmacy did not accept the prescription: " + pharmaReason(refused));
        } catch (RestClientException down) {
            throw new ValidationException("The pharmacy did not answer. Press Submit again — it will not be sent twice.");
        }
        if (answer == null || !answer.isSuccess() || answer.getData() == null || answer.getData().getId() == null) {
            throw new ValidationException("The pharmacy did not accept the prescription"
                    + (answer != null && answer.getMessage() != null ? ": " + answer.getMessage() : "."));
        }
        e.setRxId(answer.getData().getId());
        LocalDateTime now = LocalDateTime.now();
        e.setRxSubmittedAt(now);
        e.setUpdatedAt(now);
        encounters.saveAndFlush(e);
        audit.patient("RX_SUBMIT", p.getMrn(), "prescription " + e.getRxId() + " sent to the pharmacy, " + lines.size() + " medicine(s)");
        return open(encounterId);
    }

    /**
     * H3c (ruling L-3) — the doctor named on the prescription is the one who SUBMITS it: the doctor this login is
     * linked to (e.g. the owner-doctor covering another doctor's patient), else the doctor of the token.
     */
    private String prescriber(QueueToken t) {
        java.util.Optional<Long> mine = access.linkedProviderId();
        if (mine.isPresent()) {
            String name = queue.doctorName(mine.get());
            if (name != null) return name;
        }
        return t == null ? null : t.getProviderName();
    }

    /** H4 — the branch of the doctor who prescribes (the linked doctor of the login, else the token's doctor). */
    private Long routeOf(QueueToken t) {
        Long doctor = access.linkedProviderId().orElse(t == null ? null : t.getProviderId());
        return queue.pharmacyOf(doctor);
    }

    /**
     * H3c — what the pharmacy did with this visit's prescription: its status, the lines as they stand (with what was
     * dispensed) and every change (who, when, why, before → after). Read ON DEMAND (only when the doctor looks), never
     * on every save; if the pharmacy cannot be asked the screen says so and the visit is unaffected.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> pharmacyCopy(Long encounterId) {
        Long org = guard();
        Encounter e = scoped(encounterId, org);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("rxId", e.getRxId());
        if (e.getRxId() == null) { out.put("status", null); return out; }
        try {
            // HC-1d R5: a separate clinic's prescriptions live in its linked pharmacy (route LINKED, or NONE once the
            // link has ended - the prescription is still the clinic's to read). Only the combined org reads its own.
            boolean own;
            try { own = route().sameOrg(); }
            catch (ValidationException unknown) { throw new RestClientException("route unknown", unknown); }
            PharmaRxClient.Envelope rx = own ? pharma.get(e.getRxId()) : pharma.getLinked(e.getRxId());
            PharmaRxClient.Amendments ch = own ? pharma.amendments(e.getRxId()) : pharma.linkedAmendments(e.getRxId());
            out.put("status", rx == null || rx.getData() == null ? null : rx.getData().getStatus());
            out.put("items", rx == null || rx.getData() == null ? List.of() : rx.getData().getItems());
            out.put("amendments", ch == null || ch.getData() == null ? List.of() : ch.getData());
        } catch (RestClientException down) {
            out.put("unavailable", "The pharmacy's copy could not be read just now. Try again in a moment.");
        }
        return out;
    }

    // ── HC-1d — a SEPARATE clinic prescribing to the pharmacy it is linked to ─────────────────────────────────────

    /** auth's answer for THIS clinic (R1). Unreachable → refused, never "same org": that fallback is the bug HC-1d fixes. */
    record Route(String kind, String pharmacyName) {
        boolean linked() { return "LINKED".equals(kind); }
        boolean none() { return "NONE".equals(kind); }
        boolean sameOrg() { return "SAME_ORG".equals(kind); }
    }

    Route route() {
        Map<String, Object> body;
        try {
            body = routes.route();
        } catch (RestClientException down) {
            throw new ValidationException("Could not check which pharmacy your clinic sends to. Try again in a moment — nothing was sent.");
        }
        Object data = body == null ? null : body.get("data");
        Map<?, ?> r = data instanceof Map<?, ?> m ? m : Map.of();
        Object kind = r.get("route");
        if (kind == null) throw new ValidationException("Could not check which pharmacy your clinic sends to. Try again in a moment — nothing was sent.");
        Object name = r.get("pharmacyName");
        return new Route(String.valueOf(kind), name == null ? null : String.valueOf(name));
    }

    private static RxRefusedException noLink() {
        return new RxRefusedException(RxRefusedException.NO_LINKED_PHARMACY,
                "Your clinic is not linked to a pharmacy. Link one on Linked pharmacy, then send the prescription.");
    }

    /**
     * HC-1d R2 — the medicine list the doctor picks from. LINKED: the linked pharmacy's (id, name, strength — pharma
     * strips everything else). NONE: nothing — the screen says "Link your pharmacy first". SAME_ORG: the screen keeps
     * reading this business's own catalogue, as before.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> medicines() {
        guard();
        Route route = route();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("route", route.kind());
        if (!route.linked()) return out;
        Map<String, Object> answer;
        try {
            answer = pharma.linkedMedicines();
        } catch (RestClientResponseException refused) {
            if (refusedFor(refused, RxRefusedException.NO_LINKED_PHARMACY)) { out.put("route", "NONE"); return out; }
            throw new ValidationException("The pharmacy's medicine list could not be read: " + pharmaReason(refused));
        } catch (RestClientException down) {
            throw new ValidationException("The pharmacy's medicine list could not be read just now. Try again in a moment.");
        }
        Object data = answer == null ? null : answer.get("data");
        Map<?, ?> d = data instanceof Map<?, ?> m ? m : Map.of();
        out.put("pharmacyName", d.get("pharmacyName") != null ? d.get("pharmacyName") : route.pharmacyName());
        out.put("medicines", d.get("medicines") instanceof List<?> l ? l : List.of());
        return out;
    }

    /**
     * HC-1d R3 — what crosses to a SEPARATE pharmacy (D3): the patient's name, phone and MRN, the prescriber, the token,
     * the medicines. NOT the complaint / diagnosis, the clinic's person id or its branch. pharma re-checks the link and
     * builds its own idempotency key (clinic-<org>-enc-<id>), so a retry is still ONE prescription.
     */
    private EncounterView submitLinked(Encounter e, Patient p, QueueToken t, List<EncounterRxItem> lines, Route route) {
        PharmaRxClient.Rx rx = PharmaRxClient.Rx.builder()
                .patientName(p.getName()).patientPhone(p.getPhone())
                .doctorName(prescriber(t))
                .notes(p.getMrn())
                .tokenLabel(t == null ? null : t.getTokenLabel())
                .encounterId(e.getId())
                .items(lines.stream().map(l -> PharmaRxClient.Item.builder().productId(l.getProductId())
                        .medicineName(l.getMedicineName()).quantity(l.getQuantity()).dosage(l.getDosage())
                        .frequency(l.getFrequency()).duration(l.getDuration()).build()).toList())
                .build();
        PharmaRxClient.Envelope answer;
        try {
            answer = pharma.sendLinked(rx);
        } catch (RestClientResponseException refused) {
            if (refusedFor(refused, RxRefusedException.NO_LINKED_PHARMACY)) throw noLink();   // the link ended meanwhile
            throw new ValidationException("The pharmacy did not accept the prescription: " + pharmaReason(refused));
        } catch (RestClientException down) {
            throw new ValidationException("The pharmacy did not answer. Press Submit again — it will not be sent twice.");
        }
        if (answer == null || !answer.isSuccess() || answer.getData() == null || answer.getData().getId() == null) {
            throw new ValidationException("The pharmacy did not accept the prescription"
                    + (answer != null && answer.getMessage() != null ? ": " + answer.getMessage() : "."));
        }
        e.setRxId(answer.getData().getId());
        LocalDateTime now = LocalDateTime.now();
        e.setRxSubmittedAt(now);
        e.setUpdatedAt(now);
        encounters.saveAndFlush(e);
        String where = answer.getData().getFromOrgName() != null ? answer.getData().getFromOrgName() : route.pharmacyName();
        audit.patient("RX_SUBMIT", p.getMrn(), "prescription " + e.getRxId() + " sent to the linked pharmacy "
                + (where == null ? "" : where) + ", " + lines.size() + " medicine(s)");
        return open(e.getId());
    }

    /** True when the far side refused with this stable {@code data.code}. */
    private static boolean refusedFor(RestClientResponseException ex, String code) {
        try {
            Map<?, ?> body = ex.getResponseBodyAs(Map.class);
            Object data = body == null ? null : body.get("data");
            return data instanceof Map<?, ?> d && code.equals(d.get("code"));
        } catch (RuntimeException notJson) {
            return false;
        }
    }

    /** The far side's own sentence when it has one ("Prescriptions are not switched on…"), never a stack trace. */
    private static String pharmaReason(RestClientResponseException ex) {
        try {
            Map<?, ?> body = ex.getResponseBodyAs(Map.class);
            Object m = body == null ? null : body.get("message");
            if (m != null && !String.valueOf(m).isBlank()) return String.valueOf(m);
        } catch (RuntimeException ignored) { /* not JSON */ }
        return ex.getStatusCode().value() == 403 ? "you are not allowed to record prescriptions there." : "it refused (" + ex.getStatusCode().value() + ").";
    }

    private static RxLine rxLine(EncounterRxItem i) {
        return RxLine.builder().productId(i.getProductId()).medicineName(i.getMedicineName())
                .quantity(String.valueOf(i.getQuantity())).dosage(i.getDosage()).frequency(i.getFrequency())
                .duration(i.getDuration()).build();
    }

    /** The encounter of a token, for the screen that has the token (null when the visit has not started). */
    @Transactional
    public EncounterView byToken(Long tokenId) {
        Long org = guard();
        return encounters.findByOrganizationIdAndTokenId(org, tokenId).map(e -> open(e.getId())).orElse(null);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────

    private Encounter scoped(Long id, Long org) {
        if (id == null) throw new ResourceNotFoundException("Visit not found.");
        Encounter e = encounters.findByIdAndOrganizationId(id, org).orElseThrow(() -> new ResourceNotFoundException("Visit not found."));
        // H2 (L-2): a doctor linked to a login works only their own visits (every entry point comes through here)
        access.assertMayWorkProvider(e.getProviderId(), null);
        return e;
    }

    private String mrnOf(Encounter e, Long org) {
        return patients.findByIdAndOrganizationId(e.getPatientId(), org).map(Patient::getMrn).orElse(null);
    }

    static Identity identity(Patient p) {
        Integer age = p.getDateOfBirth() == null ? null : Period.between(p.getDateOfBirth(), TenantClock.today()).getYears();
        return Identity.builder().patientId(p.getId()).name(p.getName()).mrn(p.getMrn()).dateOfBirth(p.getDateOfBirth())
                .ageYears(age).sex(p.getSex()).phone(p.getPhone()).build();
    }

    private static NoteView noteView(ClinicalNote n) {
        return NoteView.builder().id(n.getId()).body(n.getBody()).authorUserId(n.getAuthorUserId())
                .amendsNoteId(n.getAmendsNoteId()).createdAt(n.getCreatedAt()).build();
    }

    private static EncounterView view(Encounter e, QueueToken t, Map<Long, List<NoteView>> notesBy) {
        return EncounterView.builder()
                .id(e.getId()).tokenId(e.getTokenId())
                .tokenLabel(t == null ? null : t.getTokenLabel()).tokenStatus(t == null ? null : t.getStatus())
                .status(e.getStatus()).providerId(e.getProviderId()).providerName(t == null ? null : t.getProviderName())
                .chiefComplaint(e.getChiefComplaint())
                .bloodPressure(e.getBpSystolic() == null ? null : e.getBpSystolic() + "/" + e.getBpDiastolic())
                .pulse(e.getPulse()).temperatureF(e.getTemperatureF()).spo2(e.getSpo2())
                .weightKg(e.getWeightKg()).heightCm(e.getHeightCm())
                .startedAt(e.getStartedAt()).completedAt(e.getCompletedAt())
                .notes(notesBy.getOrDefault(e.getId(), List.of()))
                .version(e.getVersion())
                .build();
    }
}
