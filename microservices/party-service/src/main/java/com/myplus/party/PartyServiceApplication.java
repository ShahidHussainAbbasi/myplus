package com.myplus.party;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * party-service — the platform's shared party/contact master. Owns ONLY common identity (name/contact/email/address/
 * type) and issues a stable {@code partyId}; every module (POS customer, vendor, pursuit student/donor/patient…) keeps
 * its domain data keyed by that id — the same party-agnostic pattern the finance ledger already uses. Never holds
 * domain data (AR, Rx, fees, loyalty…). See docs/party-contact-master-design.md.
 */
@SpringBootApplication
public class PartyServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PartyServiceApplication.class, args);
    }
}
