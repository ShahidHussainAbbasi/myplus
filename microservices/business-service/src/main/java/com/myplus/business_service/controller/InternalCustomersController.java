package com.myplus.business_service.controller;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.entity.Customer;
import com.myplus.business_service.entity.enums.CustomerType;
import com.myplus.business_service.repository.CustomerRepo;
import com.myplus.business_service.service.CustomerAccountService;
import com.myplus.business_service.service.ICustomerService;
import com.myplus.business_service.service.PartyBridgeService;
import com.myplus.commerce.contracts.dto.PartyCustomerRef;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ValidationException;

import lombok.RequiredArgsConstructor;

/**
 * HMS S1 — the pharmacy customer for a person: the one already linked to the party, else a new one. Called by
 * clinical-service when reception registers a patient, so the patient IS the customer at the till — one person
 * with two roles, never a second record typed at the counter (design §1, B-01(2)).
 *
 * <h3>Idempotent by party</h3>
 * An existing customer of this party (in the caller's organisation) is returned, oldest first, with
 * {@code created=false} — including a shopper who was a customer before they were a patient. clinical-service
 * serialises calls for one patient with a row lock, so two creates for one person cannot race here.
 *
 * <h3>Trust boundary</h3>
 * Reachable only inside the private network: the gateway routes no {@code /internal/**}. The organisation is the
 * forwarded caller's, never a field of the body.
 */
@RestController
@RequiredArgsConstructor
public class InternalCustomersController {

    private static final Logger LOG = LoggerFactory.getLogger(InternalCustomersController.class);

    private final CustomerRepo customerRepo;
    private final ICustomerService customerService;
    private final CustomerAccountService customerAccountService;
    private final PartyBridgeService partyBridgeService;

    @PostMapping("/internal/customers/for-party")
    public PartyCustomerRef forParty(@RequestBody PartyCustomerRef req) {
        Long org = CurrentUser.organizationId();
        Long user = CurrentUser.userId();
        if (org == null) throw new ValidationException("No active organisation.");
        if (req == null || req.getPartyId() == null) throw new ValidationException("A person (partyId) is required.");

        List<Customer> existing = customerRepo.findByPartyIdsScoped(List.of(req.getPartyId()), org, user);
        if (!existing.isEmpty()) {
            Customer c = existing.stream().min(Comparator.comparing(Customer::getCustomerId)).get();
            return PartyCustomerRef.builder().partyId(req.getPartyId()).customerId(c.getCustomerId())
                    .name(c.getName()).contact(c.getContact()).created(false).build();
        }

        LocalDateTime now = LocalDateTime.now();
        Customer c = new Customer();
        c.setName(req.getName() == null || req.getName().isBlank() ? req.getContact() : req.getName().trim());
        c.setContact(req.getContact());
        c.setCnic(req.getCnic());
        c.setPartyId(req.getPartyId());
        c.setOrganizationId(org);
        c.setUserId(user);
        c.setCustomerType(CustomerType.orDefault(null));
        c.setDated(now);
        c.setUpdated(now);
        Customer saved = customerService.save(c);

        // As addCustomer: its own credit account until an owner groups it — contained, never fatal.
        try {
            customerAccountService.stampSelfAsCreditAccount(saved);
        } catch (Exception stampFailed) {
            LOG.warn("Customer {} saved but its credit account could not be stamped", saved.getCustomerId(), stampFailed);
        }
        // Records the CUSTOMER role on the same person (party-service matches the phone back to this party).
        partyBridgeService.rebridgeCustomer(saved);

        return PartyCustomerRef.builder().partyId(req.getPartyId()).customerId(saved.getCustomerId())
                .name(saved.getName()).contact(saved.getContact()).created(true).build();
    }
}
