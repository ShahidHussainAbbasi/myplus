package com.myplus.business_service.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.entity.TaxMode;
import com.myplus.business_service.entity.TaxSetting;
import com.myplus.business_service.service.TaxService;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.dto.TaxPolicyView;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.web.exception.ValidationException;

/**
 * Publishes the calling tenant's sales-tax policy so other channels price the way the BOOKS will.
 *
 * <h3>The defect this closes</h3>
 * Marketplace's checkout ran its own tax engine — {@code net × item.taxRate / 100} — with no tenant switch,
 * no org default and no INCLUSIVE handling. business-service, meanwhile, gates every line on
 * {@code tax_setting.enabled}, which DEFAULTS TO FALSE when a tenant has no row. So a shop with tax off was
 * shown a tax line and quoted a total its own invoice then contradicted: quoted 22, invoiced 20. Each side
 * was locally correct; only the disagreement between them was wrong.
 *
 * <h3>Why it publishes POLICY and not a computed figure</h3>
 * Returning "the tax for these lines" would have put a second engine on the wire and made every quote a
 * round trip. The arithmetic instead lives once in {@code com.myplus.commerce.domain.TaxMath}, which this
 * service and every channel call. business-service keeps what it actually owns — whether tax applies, in
 * which mode, at what fallback rate — because that lives with the books.
 *
 * <h3>Trust boundary</h3>
 * Reachable only inside the private network ({@code /internal/**} is not routed by the gateway) and the
 * tenant is taken from the CALLER's forwarded identity, never from a parameter. A channel therefore cannot
 * ask for another tenant's policy: it has no way to name one. Read-only, so there is nothing to make
 * idempotent.
 */
@RestController
@RequestMapping("/internal/tax-policy")
public class InternalTaxPolicyController {

    private static final Logger LOG = LoggerFactory.getLogger(InternalTaxPolicyController.class);

    @Autowired
    private TaxService taxService;

    @Autowired
    private RequestUtil requestUtil;

    @GetMapping
    public ResponseEntity<TaxPolicyView> policy() {
        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long org = (user == null) ? null : user.getOrganizationId();
        if (org == null) throw new ValidationException("No tenant identity on the request");

        // settingsFor() already returns a disabled default when the tenant has no row, which is the correct
        // answer rather than an error: "not configured" means "charges no sales tax", exactly as the sale
        // path treats it. Answering 404 here would push every channel into inventing its own fallback —
        // which is the very habit that produced two disagreeing tax engines.
        TaxSetting s = taxService.settingsFor(org);

        TaxPolicyView out = TaxPolicyView.builder()
                .enabled(Boolean.TRUE.equals(s.getEnabled()))
                .mode(s.getTaxMode() != null ? s.getTaxMode().name() : TaxMode.EXCLUSIVE.name())
                .defaultRate(s.getDefaultRate())
                .build();

        LOG.debug("tax policy for org {}: enabled={} mode={} default={}",
                org, out.isEnabled(), out.getMode(), out.getDefaultRate());
        return ResponseEntity.ok(out);
    }
}
