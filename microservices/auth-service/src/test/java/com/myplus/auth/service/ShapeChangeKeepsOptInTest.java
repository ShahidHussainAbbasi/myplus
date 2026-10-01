package com.myplus.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.myplus.auth.entity.OrgSetting;
import com.myplus.auth.entity.Organization;
import com.myplus.auth.repository.MembershipRepository;
import com.myplus.auth.repository.OrganizationRepository;
import com.myplus.auth.repository.UserRepository;
import com.myplus.common.settings.Capability;

/**
 * EX-0a — a business-type change never touches an opt-in module.
 *
 * <p>{@code applyShape} clears every {@code org.cap.*} override so the new preset applies. No preset includes an
 * opt-in module, so before EX-0a that clear would have switched Expense management — and the records behind it
 * — off, silently. These pin both halves: the switch survives, and the confirmation never lists it.
 */
class ShapeChangeKeepsOptInTest {

    private static final long ORG = 1L;

    private record Fx(OrganizationAdminService svc, com.myplus.auth.repository.OrgSettingRepository settings,
                      com.myplus.common.settings.CapabilityService caps) { }

    private static OrgSetting row(String key, String value) {
        return OrgSetting.builder().organizationId(ORG).settingKey(key).settingValue(value).build();
    }

    private static Fx fixture(List<OrgSetting> overrides) {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        Organization o = new Organization();
        o.setId(ORG);
        o.setName("Shop");
        o.setPlan("FREE");
        o.setStatus("ACTIVE");
        when(orgs.findById(any())).thenReturn(Optional.of(o));

        var settingsRepo = mock(com.myplus.auth.repository.OrgSettingRepository.class);
        when(settingsRepo.findByOrganizationIdAndSettingKeyStartingWith(eq(ORG), eq("org.cap.")))
                .thenReturn(overrides);
        when(settingsRepo.findByOrganizationIdAndSettingKey(any(), any())).thenReturn(Optional.empty());

        var history = mock(com.myplus.auth.repository.OrgShapeHistoryRepository.class);
        when(history.save(any())).thenAnswer(i -> {
            com.myplus.auth.entity.OrgShapeHistory h = i.getArgument(0);
            h.setId(501L);
            return h;
        });
        var caps = mock(com.myplus.common.settings.CapabilityService.class);

        OrganizationAdminService svc = new OrganizationAdminService(
                orgs, mock(MembershipRepository.class), mock(UserRepository.class),
                mock(com.myplus.auth.config.JpaEntitlementSource.class), settingsRepo, history,
                mock(com.myplus.common.settings.SettingsService.class), caps, mock(ControlPlaneAuditService.class));
        return new Fx(svc, settingsRepo, caps);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("⭐ the owner's Expense management switch survives a business-type change")
    void opt_in_override_is_kept() {
        OrgSetting expenses = row("org.cap.expenseManagement", "true");
        OrgSetting installments = row("org.cap.installments", "false");
        Fx fx = fixture(List.of(expenses, installments));

        fx.svc().changeShape(ORG, "pharmacy", "corrected trade", 9L);

        ArgumentCaptor<Iterable<OrgSetting>> deleted = ArgumentCaptor.forClass(Iterable.class);
        verify(fx.settings()).deleteAll(deleted.capture());
        assertThat(deleted.getValue())
                .as("an ordinary override is still cleared — the new preset must apply (positive control)")
                .contains(installments)
                .as("the opt-in module's switch is not the shape's to clear")
                .doesNotContain(expenses);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("the preview never claims the module is turning on or off")
    void preview_never_lists_opt_in() {
        Fx fx = fixture(List.of());
        // ON today; no shape presets it — the exact case a naive preview would report as "Turning OFF".
        when(fx.caps().isEnabledFor(ORG, Capability.EXPENSE_MANAGEMENT)).thenReturn(true);
        // Positive control: installments ON today, and pharmacy does not preset it → it IS turning off.
        when(fx.caps().isEnabledFor(ORG, Capability.INSTALLMENTS)).thenReturn(true);

        Map<String, Object> p = fx.svc().previewShape(ORG, "pharmacy");

        assertThat((List<String>) p.get("turningOff"))
                .contains(Capability.INSTALLMENTS.label())
                .doesNotContain(Capability.EXPENSE_MANAGEMENT.label());
        assertThat((List<String>) p.get("turningOn")).doesNotContain(Capability.EXPENSE_MANAGEMENT.label());
    }
}
