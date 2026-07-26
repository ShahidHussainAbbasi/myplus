package com.myplus.business_service.service;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.commerce.contracts.dto.PartyRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Party bridge (P1): on write, find-or-create a shared party (party-service) for a Customer/Vender and stamp its
 * {@code party_id} — so the same person resolves to ONE identity across modules (the Item→Product master-sync pattern,
 * applied to contacts). BEST-EFFORT: a party-service hiccup must never fail or slow the domain write — it's caught and
 * logged, and re-attempted on the next write. Only bridges when {@code party_id} is still null, so the repeat-sale hot
 * path pays nothing once a customer is bridged. The stamp is a targeted update (no full-entity save → no clobber).
 */
@Service
public class PartyBridgeService {

    private static final Logger LOG = LoggerFactory.getLogger(PartyBridgeService.class);

    @Autowired(required = false)
    private PartyClient partyClient;   // null if party-service is unwired in this deployment

    @Autowired
    private CustomerRepo customerRepo;

    @Autowired
    private VenderRepo venderRepo;

    /** Bridge a customer to a party (best-effort, once). No-op if already bridged or party-service is unavailable. */
    @Transactional
    public void bridgeCustomer(Customer c) {
        if (partyClient == null || c == null || c.getCustomerId() == null || c.getPartyId() != null) return;
        try {
            PartyRef ref = partyClient.upsert(PartyRef.builder()
                    .partyType("CUSTOMER").name(c.getName()).contact(c.getContact()).email(c.getEmail())
                    .address(c.getAddress()).build());
            if (ref != null && ref.getId() != null) {
                customerRepo.updatePartyId(c.getCustomerId(), ref.getId());
                c.setPartyId(ref.getId());
            }
        } catch (Exception e) {
            LOG.warn("party bridge (customer {}) failed — will retry on next write", c.getCustomerId(), e);
        }
    }

    /** Bridge a vendor to a party (best-effort, once). */
    @Transactional
    public void bridgeVender(Vender v) {
        if (partyClient == null || v == null || v.getId() == null || v.getPartyId() != null) return;
        try {
            PartyRef ref = partyClient.upsert(PartyRef.builder()
                    .partyType("VENDOR").name(v.getName()).contact(v.getMobile()).email(v.getEmail())
                    .address(v.getAddress()).build());
            if (ref != null && ref.getId() != null) {
                venderRepo.updatePartyId(v.getId(), ref.getId());
                v.setPartyId(ref.getId());
            }
        } catch (Exception e) {
            LOG.warn("party bridge (vender {}) failed — will retry on next write", v.getId(), e);
        }
    }
}
