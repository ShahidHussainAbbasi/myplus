package com.myplus.education.service;

import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.commerce.contracts.dto.PartyRef;
import com.myplus.education.entity.Student;
import com.myplus.education.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Party bridge (P3): on write, find-or-create a shared party (party-service) for a Student and stamp its
 * {@code party_id} — so a student who is also a POS customer / pharmacy patient in the same org resolves to ONE
 * identity (the master-sync pattern from business P1). BEST-EFFORT: a party-service hiccup never fails the student
 * write. Only bridges when {@code party_id} is null (one-time). Targeted stamp (no full-entity save → no clobber).
 */
@Service
public class PartyBridgeService {

    private static final Logger LOG = LoggerFactory.getLogger(PartyBridgeService.class);

    @Autowired(required = false)
    private PartyClient partyClient;   // null if party-service is unwired in this deployment

    @Autowired
    private StudentRepository studentRepository;

    /** Bridge a student to a party (best-effort, once). No-op if already bridged or party-service is unavailable. */
    @Transactional
    public void bridgeStudent(Student s) {
        if (partyClient == null || s == null || s.getId() == null || s.getPartyId() != null) return;
        try {
            PartyRef ref = partyClient.upsert(PartyRef.builder()
                    .partyType("STUDENT").name(s.getName()).contact(s.getMobile()).email(s.getEmail())
                    .address(s.getAddress()).build());
            if (ref != null && ref.getId() != null) {
                studentRepository.updatePartyId(s.getId(), ref.getId());
                s.setPartyId(ref.getId());
            }
        } catch (Exception e) {
            LOG.warn("party bridge (student {}) failed — will retry on next write", s.getId(), e);
        }
    }
}
