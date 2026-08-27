package com.myplus.common.settings;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.web.ApiResponse;

/**
 * C3 — the capability map a screen needs, for the CALLER's tenant.
 *
 * <h3>Why this endpoint had to exist before any markup could be tagged</h3>
 * C1 built {@link CapabilityService} and its catalog, and both were correct — but nothing reached them. No
 * controller served the map and no call site guarded on it, so the library was <b>shipped unreachable</b>: the
 * exact failure {@link CapabilityCatalog}'s own javadoc warns about, one level up. C3 is the slice that makes
 * C1 real, and it starts here.
 *
 * <h3>What it replaces</h3>
 * The dashboard decides today from {@code [data-vertical-only]} and {@code [data-feature]}, both resolved in
 * the browser against a <b>hardcoded {@code VERTICALS} map in module-theme.js</b>. That is not a control: the
 * list ships to the client, {@code window.MODULE} is editable in devtools, and the API answers whoever asks
 * either way. This endpoint moves the decision server-side, where the tenant's identity comes from the JWT and
 * cannot be edited by the person the decision is about.
 *
 * <p><b>It does not, on its own, make anything safe.</b> A visibility map is for rendering. The half that
 * matters is {@link CapabilityService#assertEnabled} on the write paths — hiding a menu never was security,
 * and an endpoint that lists what is switched on would be a poor substitute for one that refuses.
 *
 * <h3>Readable by any member, like the settings catalog</h3>
 * Same rule as {@link SettingsController#list()}: reading is open to any authenticated member because the
 * screen cannot render without it; only writing is owner/admin. There is no write here at all — a capability
 * is changed through the Configuration screen, as the setting it is.
 *
 * <p>Scoping is implicit and that is deliberate: the map is always for {@code CurrentUser.organizationId()},
 * with no parameter to name another tenant. An endpoint that accepted an org id would be an IDOR waiting to
 * be found, and no screen has a reason to ask about a tenant that is not its own.
 */
@RestController
@RequestMapping("/capabilities")
public class CapabilityController {

    private final CapabilityService capabilities;

    public CapabilityController(CapabilityService capabilities) {
        this.capabilities = capabilities;
    }

    /**
     * Every capability and whether it is on, keyed by the short code {@code [data-capability]} carries.
     *
     * <p>One call rather than one per capability: the dashboard decides visibility for ~33 sections at load,
     * and a round trip each would be its own performance problem. The read costs a map lookup, not a query —
     * {@link SettingsService} holds the tenant's override map behind a bounded per-tenant Caffeine cache.
     */
    @GetMapping
    public ApiResponse<Map<String, Boolean>> list() {
        return ApiResponse.success(capabilities.enabledMap());
    }
}
