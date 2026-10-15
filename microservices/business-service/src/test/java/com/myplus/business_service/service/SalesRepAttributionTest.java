package com.myplus.business_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.myplus.business_service.entity.SalesRep;
import com.myplus.common.web.exception.ValidationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * SRA-3 — the one rule that decides who is credited with a sale (design: sra-sales-by-rep-and-area.md §6).
 * Org 7 has two reps: Asif (id 11, linked to login 101) and Bilal (id 12, no login). Org 8's rep 99 is foreign.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesRepAttributionTest {

    private static final Long ORG = 7L;
    private static final Long ASIF_LOGIN = 101L, CLERK_LOGIN = 200L;

    @Mock SalesRepService reps;
    SalesRepAttribution attribution;

    private final SalesRep asif = SalesRep.builder().id(11L).name("Asif").build();
    private final SalesRep bilal = SalesRep.builder().id(12L).name("Bilal").build();

    @BeforeEach
    void setUp() {
        attribution = new SalesRepAttribution(reps);
        when(reps.repForUser(ORG, ASIF_LOGIN)).thenReturn(Optional.of(asif));
        when(reps.repForUser(ORG, CLERK_LOGIN)).thenReturn(Optional.empty());
        when(reps.inOrg(11L, ORG)).thenReturn(Optional.of(asif));
        when(reps.inOrg(12L, ORG)).thenReturn(Optional.of(bilal));
        when(reps.inOrg(99L, ORG)).thenReturn(Optional.empty());
    }

    @Test @DisplayName("a rep booking signed in is credited themselves — REP_LOGIN")
    void repLogin() {
        var c = attribution.decide(ORG, ASIF_LOGIN, null, false, "FIELD");
        assertThat(c.repId()).isEqualTo(11L);
        assertThat(c.repName()).isEqualTo("Asif");
        assertThat(c.source()).isEqualTo(SalesRepAttribution.REP_LOGIN);
    }

    @Test @DisplayName("a rep cannot book a sale in a colleague's name — the choice is ignored without rep.manage")
    void repCannotCreditColleague() {
        var c = attribution.decide(ORG, ASIF_LOGIN, 12L, false, null);
        assertThat(c.repId()).isEqualTo(11L);
        assertThat(c.source()).isEqualTo(SalesRepAttribution.REP_LOGIN);
    }

    @Test @DisplayName("a rep who may manage reps can key another rep's paper order — REP_PAPER")
    void managerRepKeysForColleague() {
        var c = attribution.decide(ORG, ASIF_LOGIN, 12L, true, null);
        assertThat(c.repId()).isEqualTo(12L);
        assertThat(c.source()).isEqualTo(SalesRepAttribution.REP_PAPER);
    }

    @Test @DisplayName("the office keying a rep's paper order credits the chosen rep — REP_PAPER")
    void officeKeysPaperOrder() {
        var c = attribution.decide(ORG, CLERK_LOGIN, 12L, false, null);
        assertThat(c.repId()).isEqualTo(12L);
        assertThat(c.repName()).isEqualTo("Bilal");
        assertThat(c.source()).isEqualTo(SalesRepAttribution.REP_PAPER);
    }

    @Test @DisplayName("a foreign or unknown rep id is REFUSED, never silently credited to nobody")
    void foreignRepRefused() {
        assertThatThrownBy(() -> attribution.decide(ORG, CLERK_LOGIN, 99L, true, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test @DisplayName("no rep: COUNTER at the till, OFFICE for a field order, PORTAL online; 0 means none")
    void noRepSources() {
        assertThat(attribution.decide(ORG, CLERK_LOGIN, null, false, null).source()).isEqualTo(SalesRepAttribution.COUNTER);
        assertThat(attribution.decide(ORG, CLERK_LOGIN, 0L, false, "POS").source()).isEqualTo(SalesRepAttribution.COUNTER);
        assertThat(attribution.decide(ORG, CLERK_LOGIN, null, false, "FIELD").source()).isEqualTo(SalesRepAttribution.OFFICE);
        assertThat(attribution.decide(ORG, CLERK_LOGIN, null, false, "STOREFRONT").source()).isEqualTo(SalesRepAttribution.PORTAL);
        assertThat(attribution.decide(ORG, CLERK_LOGIN, 0L, false, "MARKETPLACE").repId()).isNull();
    }
}
