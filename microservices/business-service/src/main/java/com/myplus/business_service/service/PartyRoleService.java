package com.myplus.business_service.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.myplus.business_service.dto.CustomerDTO;
import com.myplus.business_service.dto.PartyPositionDTO;
import com.myplus.business_service.dto.VenderDTO;
import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.commerce.contracts.client.PartyClient;
import com.myplus.commerce.contracts.dto.PartyRef;
import com.myplus.commerce.contracts.dto.PartyRoleRef;
import com.myplus.common.security.CurrentUser;

/**
 * DR-2 — one partner, two roles, from the screen: the "also a supplier / also a customer" badges, and the owner's
 * manual LINK and UNLINK for the cases matching cannot decide.
 *
 * <h3>Order of writes for link / unlink</h3>
 * party-service first, OUTSIDE any local transaction (no DB connection held across a network call), then the local
 * {@code party_id} stamp and the audit row in ONE local transaction. A failure between the two leaves party-service
 * ahead of the local stamp; re-running the action converges, because a role link is idempotent and MOVES. An unlink
 * re-run creates a second new partner, and the first is left holding nothing — harmless, and visible as such.
 *
 * <h3>Not yet</h3>
 * "Refused when either record has set-off documents" arrives with DR-4, when a set-off document exists to check.
 */
@Service
public class PartyRoleService {

    /** A refusal the owner can act on — shown verbatim, never a stack trace. */
    public static class Refusal extends RuntimeException {
        public Refusal(String message) { super(message); }
    }

    private static final int IN_CHUNK = 1000;   // keep IN (...) lists bounded on a large customer grid

    @Autowired private CustomerRepo customerRepo;
    @Autowired private VenderRepo venderRepo;
    @Autowired private AuditService auditService;
    @Autowired private PartySetOffService setOffs;
    @Autowired private com.myplus.business_service.repository.PartySetOffRepo setOffRepo;
    @Autowired(required = false) private PartyClient partyClient;
    private final TransactionTemplate tx;

    public PartyRoleService(PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
    }

    private static Long org() { return CurrentUser.organizationId(); }
    private static Long user() { return CurrentUser.userId(); }

    // ---- badges -----------------------------------------------------------------------------------------------------

    /** Mark each customer whose partner also holds a supplier record here. ONE query (per 1,000 rows). */
    public void markCustomers(List<CustomerDTO> rows) {
        Set<Long> hits = among(rows, CustomerDTO::getPartyId, ids -> venderRepo.partyIdsAmong(ids, org(), user()));
        for (CustomerDTO r : rows) r.setAlsoSupplier(r.getPartyId() != null && hits.contains(r.getPartyId()));
    }

    /** Mark each supplier whose partner also holds a customer record here. */
    public void markVenders(List<VenderDTO> rows) {
        Set<Long> hits = among(rows, VenderDTO::getPartyId, ids -> customerRepo.partyIdsAmong(ids, org(), user()));
        for (VenderDTO r : rows) r.setAlsoCustomer(r.getPartyId() != null && hits.contains(r.getPartyId()));
    }

    private static <T> Set<Long> among(List<T> rows, Function<T, Long> partyOf, Function<Collection<Long>, List<Long>> query) {
        Set<Long> hits = new HashSet<>();
        if (rows == null || rows.isEmpty()) return hits;
        List<Long> ids = rows.stream().map(partyOf).filter(Objects::nonNull).distinct().toList();
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            hits.addAll(query.apply(new ArrayList<>(ids.subList(i, Math.min(ids.size(), i + IN_CHUNK)))));
        }
        return hits;
    }

    // ---- position (DR-3) ---------------------------------------------------------------------------------------------

    /**
     * What this partner owes us and what we owe them, from every customer and supplier record it holds in this
     * tenant. Read-only. A partner with no record here reads as not found — the same answer for another tenant's
     * partner as for one that does not exist (anti-IDOR).
     */
    public PartyPositionDTO position(Long partyId) {
        if (partyId == null) throw new Refusal("Choose a partner.");
        List<Customer> cs = customerRepo.findByPartyIdsScoped(List.of(partyId), org(), user());
        List<Vender> vs = venderRepo.findByPartyIdScoped(partyId, org(), user());
        if (cs.isEmpty() && vs.isEmpty()) throw new Refusal("No customer or supplier here belongs to this partner.");

        PartyPositionDTO p = new PartyPositionDTO();
        p.setPartyId(partyId);
        java.math.BigDecimal receivable = java.math.BigDecimal.ZERO, payable = java.math.BigDecimal.ZERO,
                credit = java.math.BigDecimal.ZERO;
        for (Customer c : cs) {
            PartyPositionDTO.Line l = new PartyPositionDTO.Line();
            l.setId(c.getCustomerId());
            l.setName(c.getName());
            l.setDue(nz(c.getDueAmount()));
            l.setStoreCredit(nz(c.getCreditBalance()));
            receivable = receivable.add(l.getDue());
            credit = credit.add(l.getStoreCredit());
            p.getCustomers().add(l);
        }
        for (Vender v : vs) {
            PartyPositionDTO.Line l = new PartyPositionDTO.Line();
            l.setId(v.getId());
            l.setName(v.getName());
            l.setDue(nz(v.getDueAmount()));
            payable = payable.add(l.getDue());
            p.getSuppliers().add(l);
        }
        p.setReceivable(receivable);
        p.setPayable(payable);
        p.setStoreCredit(credit);
        p.setNetIfSetOff(receivable.subtract(payable));
        // DR-4: what a set-off can actually clear — ordinary invoices and bills only (never installment rows), so the
        // screen offers exactly what the guard will accept. Can be lower than min(receivable, payable).
        java.math.BigDecimal canReceive = java.math.BigDecimal.ZERO, canPay = java.math.BigDecimal.ZERO;
        for (Customer c : cs) canReceive = canReceive.add(setOffs.settleableReceivable(c.getCustomerId()));
        for (Vender v : vs) canPay = canPay.add(setOffs.settleablePayable(v.getId()));
        p.setSetOffLimit(canReceive.min(canPay));
        return p;
    }

    private static java.math.BigDecimal nz(java.math.BigDecimal v) {
        return v == null ? java.math.BigDecimal.ZERO : v;
    }

    // ---- payment hint (DR-5) ------------------------------------------------------------------------------------------

    /**
     * About to receive from a customer, or pay a supplier: does its partner also have an open balance on the OTHER
     * side? Read-only — the dialog only mentions it and links to the set-off; nothing is applied here. A record with no
     * partner, or a partner with one role, answers zero (the dialog then says nothing).
     *
     * @param role CUSTOMER (receiving) or VENDOR (paying)
     */
    public java.util.Map<String, Object> paymentHint(String role, Long id) {
        boolean receiving = "CUSTOMER".equalsIgnoreCase(role == null ? "" : role.trim());
        if (!receiving && !"VENDOR".equalsIgnoreCase(role == null ? "" : role.trim())) throw new Refusal("Unknown role: " + role);
        if (id == null) throw new Refusal("Choose a customer or a supplier.");
        Long partyId = receiving
                ? customerRepo.findByIdScoped(id, org(), user()).orElseThrow(() -> new Refusal("Customer not found.")).getPartyId()
                : venderRepo.findByIdScoped(id, org(), user()).orElseThrow(() -> new Refusal("Supplier not found.")).getPartyId();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("partyId", partyId);
        out.put("otherSide", receiving ? "VENDOR" : "CUSTOMER");
        java.math.BigDecimal other = java.math.BigDecimal.ZERO, limit = java.math.BigDecimal.ZERO;
        if (partyId != null) {
            PartyPositionDTO p = position(partyId);
            boolean hasOther = receiving ? !p.getSuppliers().isEmpty() : !p.getCustomers().isEmpty();
            if (hasOther) {
                other = receiving ? p.getPayable() : p.getReceivable();
                limit = p.getSetOffLimit();
            }
        }
        out.put("otherSideOpen", other);   // receiving: what WE owe them; paying: what THEY owe us
        out.put("setOffLimit", limit);
        return out;
    }

    // ---- link -------------------------------------------------------------------------------------------------------

    /**
     * Put a supplier on the customer's partner. The customer's partner is kept, because the customer side is where a
     * partner's history (statements, store credit, the 360 view) is usually read from.
     * @return true when something changed, false when they were already one partner.
     */
    public boolean link(Long customerId, Long venderId) {
        if (customerId == null || venderId == null) throw new Refusal("Choose a customer and a supplier.");
        Customer c = customerRepo.findByIdScoped(customerId, org(), user()).orElseThrow(() -> new Refusal("Customer not found."));
        Vender v = venderRepo.findByIdScoped(venderId, org(), user()).orElseThrow(() -> new Refusal("Supplier not found."));
        Long partyId = c.getPartyId();
        if (partyId == null) throw new Refusal("This customer is not linked to a partner yet. Save it once more, then try again.");
        if (partyId.equals(v.getPartyId())) return false;
        requireNoSetOff(c.getCustomerId(), v.getId());
        requireParty();

        partyClient.link(partyId, PartyRoleRef.builder()
                .module("business").role("VENDOR").localId(v.getId()).label(v.getName()).build());

        Long from = v.getPartyId();
        tx.executeWithoutResult(s -> {
            venderRepo.updatePartyId(v.getId(), partyId);
            auditService.record("PARTY_LINK", "VENDOR", String.valueOf(v.getId()), null,
                    "Supplier " + v.getName() + " linked to customer " + c.getName() + " (partner " + from + " -> " + partyId + ")");
        });
        return true;
    }

    // ---- unlink -----------------------------------------------------------------------------------------------------

    /** Give one record a partner of its own. {@code role} is CUSTOMER or VENDOR. Returns the new partner id. */
    public Long unlink(String role, Long id) {
        if (id == null || role == null) throw new Refusal("Choose a customer or a supplier.");
        boolean isCustomer = "CUSTOMER".equalsIgnoreCase(role.trim());
        if (!isCustomer && !"VENDOR".equalsIgnoreCase(role.trim())) throw new Refusal("Unknown role: " + role);

        Long partyId;
        String name, contact, email, address, taxId;
        if (isCustomer) {
            Customer c = customerRepo.findByIdScoped(id, org(), user()).orElseThrow(() -> new Refusal("Customer not found."));
            partyId = c.getPartyId(); name = c.getName(); contact = c.getContact(); email = c.getEmail();
            address = c.getAddress(); taxId = c.getCnic();
        } else {
            Vender v = venderRepo.findByIdScoped(id, org(), user()).orElseThrow(() -> new Refusal("Supplier not found."));
            partyId = v.getPartyId(); name = v.getName();
            contact = (v.getMobile() != null && !v.getMobile().isBlank()) ? v.getMobile() : v.getPhone();
            email = v.getEmail(); address = v.getAddress(); taxId = v.getCnicNtn();
        }
        if (partyId == null) throw new Refusal("This record is not linked to a partner, so there is nothing to unlink.");
        long sharing = customerRepo.countByPartyScoped(partyId, org(), user()) + venderRepo.countByPartyScoped(partyId, org(), user());
        if (sharing < 2) throw new Refusal("This record does not share its partner with another customer or supplier.");
        requireNoSetOff(isCustomer ? id : -1L, isCustomer ? -1L : id);
        requireParty();

        String roleName = isCustomer ? "CUSTOMER" : "VENDOR";
        PartyRef created = partyClient.detach(PartyRef.builder()
                .partyType(roleName).name(name).contact(contact).email(email).address(address).taxId(taxId)
                .role(PartyRoleRef.builder().module("business").role(roleName).localId(id).label(name).build())
                .build());
        if (created == null || created.getId() == null) throw new IllegalStateException("party-service returned no partner");

        Long newId = created.getId();
        tx.executeWithoutResult(s -> {
            if (isCustomer) customerRepo.updatePartyId(id, newId); else venderRepo.updatePartyId(id, newId);
            auditService.record("PARTY_UNLINK", roleName, String.valueOf(id), null,
                    (isCustomer ? "Customer " : "Supplier ") + name + " given its own partner (" + partyId + " -> " + newId + ")");
        });
        return newId;
    }

    /** A set-off that still stands ties the two records together; reverse it before moving either. */
    private void requireNoSetOff(Long customerId, Long venderId) {
        if (setOffRepo.countPostedFor(org(), customerId, venderId) > 0) {
            throw new Refusal("A set-off stands on this record. Reverse it before linking or unlinking.");
        }
    }

    private void requireParty() {
        if (partyClient == null) throw new Refusal("Partners are not available in this installation.");
    }
}
