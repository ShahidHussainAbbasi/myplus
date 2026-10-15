package com.myplus.clinical.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.clinical.entity.Patient;
import com.myplus.clinical.repository.PatientRepo;
import com.myplus.common.docnum.DocumentNumberService;
import com.myplus.common.security.time.TenantClock;

import lombok.RequiredArgsConstructor;

/**
 * HMS S1 — the transactional writes, in their own bean so every call passes through the transaction proxy
 * (a self-call would silently run without one).
 *
 * <p>{@link #insert} takes the MRN LAST, immediately before the insert, and makes no remote call: the MRN counter
 * row is locked until commit, and every other front desk of the clinic waits behind it (common-docnum, "allocate
 * late, never before a remote call").
 */
@Service
@RequiredArgsConstructor
public class PatientWriter {

    public static final String MRN_DOC_TYPE = "MRN";

    private final PatientRepo repo;
    private final DocumentNumberService numbers;

    /**
     * Insert, numbered. A second insert of the same phone (two desks at once) fails on uq_patient_phone and the
     * whole transaction — MRN included — rolls back, so no number is burned.
     */
    @Transactional
    public Patient insert(Patient p, String mrnCode) {
        LocalDateTime now = LocalDateTime.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        long seq = numbers.next(p.getOrganizationId(), MRN_DOC_TYPE);
        p.setMrn(PatientRules.mrn(mrnCode, p.getOrganizationId(), TenantClock.today(), seq));
        return repo.saveAndFlush(p);
    }

    @Transactional
    public Patient save(Patient p) {
        p.setUpdatedAt(LocalDateTime.now());
        return repo.saveAndFlush(p);
    }
}
