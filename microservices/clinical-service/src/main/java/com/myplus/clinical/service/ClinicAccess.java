package com.myplus.clinical.service;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ValidationException;

/**
 * Who may do what, in one place.
 *
 * <h3>The capability check fails CLOSED, on reads too</h3>
 * The clinic is an opt-in module. Unlike most modules, a READ is refused without it as well: these rows are
 * patients, and "the module is off" must mean no patient data leaves the service. An unresolved capability set
 * (null) is refused too.
 *
 * <h3>Tiers (S1)</h3>
 * Every member of the clinic may register and find patients (the front desk). Dedicated RECEPTION / DOCTOR /
 * PHARMACIST privileges arrive with the doctor's workspace (S3), which is the first screen holding clinical data.
 */
@Component
public class ClinicAccess {

    public static final String CAPABILITY = "clinic";

    public Long org() {
        Long org = CurrentUser.organizationId();
        if (org == null) throw new ValidationException("No active organisation.");
        return org;
    }

    public Long userId() { return CurrentUser.userId(); }

    public void assertModuleOn() {
        Set<String> caps = CurrentUser.capabilities();
        if (caps == null || !caps.contains(CAPABILITY)) {
            throw new ValidationException("The clinic is not switched on for this business. "
                    + "An owner can switch it on in Settings → Configuration.");
        }
    }
}
