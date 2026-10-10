package com.myplus.clinical.service;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.clinical.dto.PatientDtos.PatientView;
import com.myplus.clinical.dto.PatientDtos.PhoneLookup;
import com.myplus.clinical.dto.PatientDtos.RegisterRequest;
import com.myplus.clinical.dto.PatientDtos.UpdateRequest;
import com.myplus.clinical.entity.Patient;
import com.myplus.clinical.repository.PatientRepo;
import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.PartyCustomerRef;
import com.myplus.commerce.contracts.dto.PartyRef;
import com.myplus.commerce.contracts.dto.PartyRoleRef;
import com.myplus.common.security.time.TenantClock;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * HMS S1 — the front desk: find a patient by phone, register one, correct one.
 *
 * <h3>One patient per phone (client rule, design §1)</h3>
 * A known phone is never registered again: the refusal carries the patient already on it, so the screen opens them
 * ({@link PatientExistsException}). The UNIQUE index decides, not the pre-check — two desks typing one number at
 * once both pass the read, and the loser's insert is turned into the same answer.
 *
 * <h3>One person, two roles (design §1, B-01(2))</h3>
 * After the patient commits, the person is upserted in party-service with role PATIENT and the pharmacy customer
 * is found-or-created for that person in business-service — so the patient registered here is the customer the
 * pharmacy sells to, never a second record. Neither call can lose a registration: a failure leaves
 * {@code linkPending} on the patient and {@link #link} completes it later (the screen offers it; dispensing will
 * retry it in S4).
 */
@Service
@RequiredArgsConstructor
public class PatientService {

    private static final Logger LOG = LoggerFactory.getLogger(PatientService.class);
    private static final int PAGE = 20;

    private final PatientRepo repo;
    private final PatientWriter writer;
    private final ClinicAccess access;
    private final ClinicSettings settings;
    private final ClinicAuditService audit;
    private final PartyClient party;
    private final TradeClient trade;
    private final TransactionTemplate tx;
    private final com.myplus.clinical.repository.QueueTokenRepo tokens;

    /** Who is on this number. Refuses a number that is not a mobile, so the screen can say so as it is typed. */
    public PhoneLookup lookup(String rawPhone) {
        access.assertModuleOn();
        Long org = access.org();
        String phone = PatientRules.phone(rawPhone);
        List<Patient> found = repo.findByOrganizationIdAndPhoneKeyOrderByFamilySeqAsc(org, PhoneNumbers.key(phone));
        found.forEach(p -> audit.patient("PATIENT_LOOKUP", p.getMrn(), "found by phone at the front desk"));
        return PhoneLookup.builder()
                .phone(phone)
                .patients(found.stream().filter(p -> Patient.ACTIVE.equals(p.getStatus())).map(p -> {
                    PatientView v = view(p);
                    // S2 (02c): the doctor of the latest token — the screen preselects them
                    tokens.findFirstByOrganizationIdAndPatientIdOrderByIdDesc(org, p.getId())
                            .ifPresent(t -> v.setLastProviderId(t.getProviderId()));
                    return v;
                }).toList())
                .familyAllowed(settings.familyOnOnePhone())
                .cnicRequired(settings.cnicRequired())
                .build();
    }

    public PatientView register(RegisterRequest req) {
        access.assertModuleOn();
        Long org = access.org();
        String phone = PatientRules.phone(req.getPhone());
        String key = PhoneNumbers.key(phone);
        boolean family = Boolean.TRUE.equals(req.getAddFamilyMember());

        Patient p = new Patient();
        p.setOrganizationId(org);
        p.setPhone(phone);
        p.setPhoneKey(key);
        p.setName(PatientRules.name(req.getName(), phone));
        p.setCnic(PatientRules.cnic(req.getCnic(), settings.cnicRequired()));
        p.setDateOfBirth(PatientRules.dateOfBirth(req.getDateOfBirth(), TenantClock.today()));
        p.setSex(PatientRules.sex(req.getSex()));
        p.setCreatedBy(access.userId());

        List<Patient> onPhone = repo.findByOrganizationIdAndPhoneKeyOrderByFamilySeqAsc(org, key);
        if (family) {
            if (!settings.familyOnOnePhone()) {
                throw new ValidationException("This clinic registers one patient per phone. An owner can allow family "
                        + "members on one phone in Settings → Clinic.");
            }
            if (onPhone.isEmpty()) {
                throw new ValidationException("Register the phone's holder first; family members are added on a known number.");
            }
            boolean sameName = onPhone.stream().anyMatch(x -> x.getName().equalsIgnoreCase(p.getName()));
            if (sameName) throw new ValidationException(p.getName() + " is already registered on this number.");
            p.setFamilySeq(repo.maxFamilySeq(org, key) + 1);
        } else {
            if (!onPhone.isEmpty()) throw new PatientExistsException(view(onPhone.get(0)));
            p.setFamilySeq(0);
        }

        Patient saved;
        try {
            saved = writer.insert(p, settings.mrnCode());
        } catch (DataIntegrityViolationException raced) {
            // Another desk registered this number a moment ago: the same answer as the pre-check gives.
            Patient holder = repo.findByOrganizationIdAndPhoneKeyOrderByFamilySeqAsc(org, key).stream().findFirst()
                    .orElseThrow(() -> raced);
            throw new PatientExistsException(view(holder));
        }
        audit.patient("PATIENT_REGISTER", saved.getMrn(), family ? "family member on a shared phone" : "registered");
        return linkNow(saved.getId(), org);
    }

    public PatientView get(Long id) {
        access.assertModuleOn();
        Patient p = scoped(id);
        audit.patient("PATIENT_VIEW", p.getMrn(), "opened");
        return view(p);
    }

    /** Most recent first, at most a page: the front desk's list and search box. */
    public List<PatientView> search(String q) {
        access.assertModuleOn();
        Long org = access.org();
        List<Patient> rows;
        if (q == null || q.isBlank()) {
            rows = repo.recent(org, PageRequest.of(0, PAGE));
        } else {
            String t = q.trim();
            String digits = t.replaceAll("[^0-9]", "");
            // a phone typed as +92 / 92 is searched in its stored 03… form; no digits = never matches the phone
            String phonePrefix = digits.isEmpty() ? "\u0000"
                    : digits.startsWith("92") ? "0" + digits.substring(2) : digits;
            rows = repo.search(org, t, phonePrefix, PageRequest.of(0, PAGE));
        }
        audit.patient("PATIENT_SEARCH", null, rows.size() + " shown");
        return rows.stream().map(PatientService::view).toList();
    }

    public PatientView update(Long id, UpdateRequest req) {
        access.assertModuleOn();
        Patient p = scoped(id);
        if (req.getVersion() != null && !Objects.equals(req.getVersion(), p.getVersion())) {
            throw new ValidationException("Someone else changed this patient since you opened it. Reopen and try again.");
        }
        String before = p.getName() + "|" + p.getCnic() + "|" + p.getDateOfBirth() + "|" + p.getSex();
        p.setName(PatientRules.name(req.getName(), p.getPhone()));
        p.setCnic(PatientRules.cnic(req.getCnic(), settings.cnicRequired()));
        p.setDateOfBirth(PatientRules.dateOfBirth(req.getDateOfBirth(), TenantClock.today()));
        p.setSex(PatientRules.sex(req.getSex()));
        String after = p.getName() + "|" + p.getCnic() + "|" + p.getDateOfBirth() + "|" + p.getSex();
        if (before.equals(after)) return view(p);
        Patient saved = writer.save(p);
        audit.patient("PATIENT_UPDATE", saved.getMrn(), "details corrected");
        return view(saved);
    }

    /**
     * HMS S4-lite — who did the pharmacist mean? A token of TODAY ("A-007", any case), an MRN, or a phone, resolved
     * to the patient (and so the person / party the pharmacy's prescriptions are linked to). Empty when it matches
     * nobody — the caller then searches the text as a name. Audited like a lookup.
     */
    public List<PatientView> resolve(String q) {
        access.assertModuleOn();
        Long org = access.org();
        String t = q == null ? "" : q.trim().toUpperCase(java.util.Locale.ROOT);
        List<Patient> hits = new java.util.ArrayList<>();
        if (t.matches("[A-Z]{1,2}-\\d{1,4}")) {
            String[] parts = t.split("-");
            String label = parts[0] + "-" + String.format(java.util.Locale.ROOT, "%03d", Integer.parseInt(parts[1]));
            tokens.findFirstByOrganizationIdAndVisitDateAndTokenLabel(org, TenantClock.today(), label)
                    .flatMap(tok -> repo.findByIdAndOrganizationId(tok.getPatientId(), org)).ifPresent(hits::add);
        } else if (t.startsWith("MRN-")) {
            repo.search(org, t, "\u0000", PageRequest.of(0, 1)).stream()
                    .filter(p -> p.getMrn().equalsIgnoreCase(t)).findFirst().ifPresent(hits::add);
        } else {
            String phone = PhoneNumbers.normalise(q);
            if (phone != null) hits.addAll(repo.findByOrganizationIdAndPhoneKeyOrderByFamilySeqAsc(org, PhoneNumbers.key(phone)));
        }
        hits.forEach(p -> audit.patient("PATIENT_LOOKUP", p.getMrn(), "resolved at the pharmacy"));
        return hits.stream().filter(p -> Patient.ACTIVE.equals(p.getStatus())).map(PatientService::view).toList();
    }

    /** Owner/admin (controller gate): a duplicate or a test record leaves the register; the row is kept. */
    public PatientView retire(Long id, String reason) {
        access.assertModuleOn();
        Patient p = scoped(id);
        if (Patient.RETIRED.equals(p.getStatus())) return view(p);
        p.setStatus(Patient.RETIRED);
        Patient saved = writer.save(p);
        audit.record(com.myplus.common.audit.AuditRecord.builder().action("PATIENT_RETIRE")
                .entityType(ClinicAuditService.ENTITY).entityRef(saved.getMrn())
                .reason(reason == null || reason.isBlank() ? "no reason given" : reason.trim()).build());
        return view(saved);
    }

    /** Complete a pending link (the screen's "Link customer" and, in S4, Dispense). Idempotent. */
    public PatientView link(Long id) {
        access.assertModuleOn();
        Long org = access.org();
        scoped(id);
        return linkNow(id, org);
    }

    /**
     * Person, then customer — under the patient's row lock, so two links of one patient cannot create two
     * customers (the second waits, then finds both ids stamped). The lock is on this ONE row; nothing tenant-wide
     * is held across the network. Each step is best-effort: what failed stays pending and is said so.
     */
    private PatientView linkNow(Long id, Long org) {
        Patient result = tx.execute(status -> {
            Patient p = repo.lockScoped(id, org).orElseThrow(() -> new ResourceNotFoundException("Patient not found."));
            if (p.getPartyId() == null) {
                try {
                    PartyRef ref = party.upsert(PartyRef.builder()
                            .partyType("PATIENT").name(p.getName()).contact(p.getPhone()).taxId(p.getCnic())
                            .role(PartyRoleRef.builder().module("clinic").role("PATIENT").localId(p.getId())
                                    .label(p.getMrn()).build())
                            .build());
                    if (ref != null && ref.getId() != null) p.setPartyId(ref.getId());
                } catch (RuntimeException down) {
                    LOG.warn("Patient {}: person link pending ({})", p.getMrn(), down.toString());
                }
            }
            if (p.getPartyId() != null && p.getCustomerId() == null) {
                try {
                    PartyCustomerRef c = trade.customerForParty(PartyCustomerRef.builder()
                            .partyId(p.getPartyId()).name(p.getName()).contact(p.getPhone()).cnic(p.getCnic()).build());
                    if (c != null && c.getCustomerId() != null) p.setCustomerId(c.getCustomerId());
                } catch (RuntimeException down) {
                    LOG.warn("Patient {}: customer link pending ({})", p.getMrn(), down.toString());
                }
            }
            return repo.save(p);
        });
        if (result.getCustomerId() != null) audit.patient("PATIENT_LINK", result.getMrn(), "customer " + result.getCustomerId());
        return view(result);
    }

    private Patient scoped(Long id) {
        if (id == null) throw new ResourceNotFoundException("Patient not found.");
        return repo.findByIdAndOrganizationId(id, access.org())
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found."));
    }

    static PatientView view(Patient p) {
        return PatientView.builder()
                .id(p.getId()).mrn(p.getMrn()).phone(p.getPhone()).name(p.getName()).cnic(p.getCnic())
                .dateOfBirth(p.getDateOfBirth()).sex(p.getSex()).familySeq(p.getFamilySeq()).status(p.getStatus())
                .partyId(p.getPartyId()).customerId(p.getCustomerId())
                .linkPending(p.getPartyId() == null || p.getCustomerId() == null)
                .createdAt(p.getCreatedAt()).version(p.getVersion())
                .build();
    }
}
