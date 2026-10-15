package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.myplus.auth.entity.OrgLink;
import com.myplus.auth.entity.OrgSetting;
import com.myplus.auth.entity.Organization;
import com.myplus.auth.repository.OrgLinkRepository;
import com.myplus.auth.repository.OrgSettingRepository;
import com.myplus.auth.repository.OrganizationRepository;

/** HC-1c — the link's rules: who may find, ask, accept and end; one link per clinic; both trails written. */
class OrgLinkServiceTest {

    static final long CLINIC = 1L, OPEN = 2L, CLOSED = 3L, SHOP = 4L, OTHER = 5L;

    OrgLinkRepository links = mock(OrgLinkRepository.class);
    OrganizationRepository orgs = mock(OrganizationRepository.class);
    OrgSettingRepository settings = mock(OrgSettingRepository.class);
    ControlPlaneAuditService audit = mock(ControlPlaneAuditService.class);
    OrgLinkService svc = new OrgLinkService(links, orgs, settings, audit);

    static Organization org(long id, String type, String name) {
        return Organization.builder().id(id).type(type).name(name).status("ACTIVE").build();
    }

    static OrgSetting accepts(long org) {
        return OrgSetting.builder().organizationId(org).settingKey(OrgLinkService.ACCEPT_KEY).settingValue("true").build();
    }

    @BeforeEach
    void data() {
        for (Organization o : List.of(org(CLINIC, "CLINIC", "Tahseen Clinic"), org(OPEN, "PHARMA", "Tahseen Pharmacy"),
                org(CLOSED, "PHARMA", "Tahseen Closed Pharmacy"), org(SHOP, "BUSINESS", "A Shop"), org(OTHER, "PHARMA", "Other Pharmacy"))) {
            when(orgs.findById(o.getId())).thenReturn(Optional.of(o));
            when(orgs.lockById(o.getId())).thenReturn(Optional.of(o));
        }
        when(settings.findBySettingKeyAndSettingValue(OrgLinkService.ACCEPT_KEY, "true")).thenReturn(List.of(accepts(OPEN), accepts(OTHER)));
        when(orgs.findAllById(any())).thenAnswer(inv -> {
            List<Organization> out = new java.util.ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) orgs.findById((Long) id).ifPresent(out::add);
            return out;
        });
        when(settings.findByOrganizationIdAndSettingKey(OPEN, OrgLinkService.ACCEPT_KEY)).thenReturn(Optional.of(accepts(OPEN)));
        when(settings.findByOrganizationIdAndSettingKey(OTHER, OrgLinkService.ACCEPT_KEY)).thenReturn(Optional.of(accepts(OTHER)));
        when(settings.findByOrganizationIdAndSettingKey(CLOSED, OrgLinkService.ACCEPT_KEY)).thenReturn(Optional.empty());
        when(links.findByClinicOrgIdAndStatusIn(anyLong(), any())).thenReturn(List.of());
        when(links.save(any(OrgLink.class))).thenAnswer(inv -> { OrgLink l = inv.getArgument(0); if (l.getId() == null) l.setId(77L); return l; });
    }

    static String code(Runnable r) {
        try { r.run(); } catch (OrgLinkService.LinkRefusedException e) { return e.getCode(); }
        return null;
    }

    @Test
    void only_opted_in_pharmacies_are_found_and_only_id_and_name_cross() {
        List<Map<String, Object>> found = svc.findPharmacies(CLINIC, "tahseen");
        assertThat(found).extracting(m -> m.get("name")).containsExactly("Tahseen Pharmacy");
        assertThat(found.get(0).keySet()).containsExactly("id", "name");
    }

    @Test
    void a_shop_or_a_pharmacy_cannot_search_and_a_short_query_is_refused() {
        assertThat(code(() -> svc.findPharmacies(SHOP, "tahseen"))).isEqualTo("LINK_NOT_CLINIC");
        assertThat(code(() -> svc.findPharmacies(OPEN, "tahseen"))).isEqualTo("LINK_NOT_CLINIC");
        assertThat(code(() -> svc.findPharmacies(CLINIC, "t"))).isEqualTo("LINK_QUERY_SHORT");
    }

    @Test
    void a_request_to_a_closed_pharmacy_or_a_shop_is_refused_like_one_that_does_not_exist() {
        assertThat(code(() -> svc.request(CLINIC, 9L, CLOSED))).isEqualTo("LINK_PHARMACY_CLOSED");
        assertThat(code(() -> svc.request(CLINIC, 9L, SHOP))).isEqualTo("LINK_PHARMACY_CLOSED");
        assertThat(code(() -> svc.request(CLINIC, 9L, 999L))).isEqualTo("LINK_PHARMACY_CLOSED");
        assertThat(code(() -> svc.request(SHOP, 9L, OPEN))).isEqualTo("LINK_NOT_CLINIC");
        verify(links, never()).save(any());
    }

    @Test
    void one_waiting_or_active_link_per_clinic() {
        OrgLink waiting = new OrgLink();
        waiting.setStatus(OrgLink.REQUESTED);
        when(links.findByClinicOrgIdAndStatusIn(eq(CLINIC), any())).thenReturn(List.of(waiting));
        assertThat(code(() -> svc.request(CLINIC, 9L, OPEN))).isEqualTo("LINK_ALREADY");
    }

    @Test
    void a_request_is_recorded_in_both_trails() {
        Map<String, Object> v = svc.request(CLINIC, 9L, OPEN);
        assertThat(v.get("status")).isEqualTo(OrgLink.REQUESTED);
        assertThat(v.get("otherName")).isEqualTo("Tahseen Pharmacy");
        verify(audit).linkAction(eq("LINK_REQUEST"), eq(77L), eq(CLINIC), any(), eq(OrgLink.REQUESTED), any(), eq(9L), anyString());
        verify(audit).linkAction(eq("LINK_REQUEST"), eq(77L), eq(OPEN), any(), eq(OrgLink.REQUESTED), any(), eq(9L), anyString());
    }

    @Test
    void only_the_addressed_pharmacy_accepts_everyone_else_is_told_not_found() {
        OrgLink l = new OrgLink();
        l.setId(77L); l.setClinicOrgId(CLINIC); l.setPharmacyOrgId(OPEN); l.setStatus(OrgLink.REQUESTED);
        when(links.findById(77L)).thenReturn(Optional.of(l));
        assertThat(code(() -> svc.accept(CLINIC, 9L, 77L))).isEqualTo("LINK_NOT_FOUND");
        assertThat(code(() -> svc.accept(OTHER, 9L, 77L))).isEqualTo("LINK_NOT_FOUND");
        assertThat(l.getStatus()).isEqualTo(OrgLink.REQUESTED);
        // POSITIVE CONTROL: the addressed pharmacy can.
        assertThat(svc.accept(OPEN, 8L, 77L).get("status")).isEqualTo(OrgLink.ACTIVE);
        assertThat(code(() -> svc.accept(OPEN, 8L, 77L))).as("accepting twice").isEqualTo("LINK_NOT_WAITING");
    }

    @Test
    void ending_needs_a_reason_and_only_a_side_may_end() {
        OrgLink l = new OrgLink();
        l.setId(77L); l.setClinicOrgId(CLINIC); l.setPharmacyOrgId(OPEN); l.setStatus(OrgLink.ACTIVE);
        when(links.findById(77L)).thenReturn(Optional.of(l));
        assertThat(code(() -> svc.end(CLINIC, 9L, 77L, "  "))).isEqualTo("LINK_REASON_REQUIRED");
        assertThat(code(() -> svc.end(OTHER, 9L, 77L, "why"))).isEqualTo("LINK_NOT_FOUND");
        assertThat(l.getStatus()).isEqualTo(OrgLink.ACTIVE);
        Map<String, Object> v = svc.end(OPEN, 8L, 77L, "guide cleanup");
        assertThat(v.get("status")).isEqualTo(OrgLink.ENDED);
        assertThat(v.get("endedByMe")).isEqualTo(true);
        verify(audit, times(2)).linkAction(eq("LINK_END"), eq(77L), anyLong(), eq(OrgLink.ACTIVE), eq(OrgLink.ENDED), eq("guide cleanup"), eq(8L), anyString());
    }

    @Test
    void only_a_pharmacy_has_the_accept_clinics_switch() {
        assertThat(code(() -> svc.setAcceptsClinics(CLINIC, 9L, true))).isEqualTo("LINK_NOT_PHARMACY");
        assertThat(code(() -> svc.setAcceptsClinics(SHOP, 9L, true))).isEqualTo("LINK_NOT_PHARMACY");
    }
}
