package com.myplus.business_service.controller;

import com.myplus.business_service.service.OpeningBalanceService;
import com.myplus.business_service.util.GenericResponse;
import com.myplus.common.security.CurrentUser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * OB-1 — recording what customers and suppliers owed at cutover.
 *
 * <h3>⚠ Every endpoint here writes to the GENERAL LEDGER, so none of them is ordinary data entry</h3>
 * An opening balance is the easiest place in the whole product to hide a fabricated receivable: it needs no
 * goods, no delivery and no counterparty agreement, and it lands straight in a customer's balance and in the
 * books. Gated to owner/admin, and every posting carries who entered it.
 *
 * <p><b>The TENANT owns this, not the platform operator.</b> An operator does not know what a customer owed
 * before the shop migrated, and inventing that figure on a customer's behalf is exactly what a support
 * organisation must never be able to do. The operator's role is the template, the validation and — if asked
 * — a time-limited audited support session; never routine posting.
 */
@RestController
public class OpeningBalanceController {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpeningBalanceController.class);

    /**
     * Owner or admin. Copied from {@code InstallmentController} rather than invented, so this service has
     * ONE shape of owner-gate and not two — the same reasoning that expression carries there.
     */
    private static final String OWNER_ONLY =
            "hasAuthority('ROLE_OWNER') or hasAuthority('SUPER_PRIVILEGE') or hasAuthority('ADMIN_PRIVILEGE')";

    @Autowired private OpeningBalanceService openingBalanceService;

    private Long orgId() { return CurrentUser.organizationId(); }
    private Long userId() { return CurrentUser.userId(); }

    /** The migration's state: the cutover date, whether it is locked, and what has been entered so far. */
    @GetMapping("/openingBalanceSummary")
    @PreAuthorize(OWNER_ONLY)
    public GenericResponse summary() {
        try {
            return new GenericResponse("SUCCESS", "summary", openingBalanceService.summary(orgId()));
        } catch (Exception e) {
            LOGGER.error("openingBalanceSummary failed", e);
            return new GenericResponse("FAILED", e.getMessage());
        }
    }

    /**
     * Record what one party owed at cutover.
     *
     * <p>One endpoint for both sides: pass {@code customerId} OR {@code venderId}. They are the same act
     * against opposite signs of the ledger, and splitting them into two endpoints would mean two places to
     * forget the cutover check, the org check and the lock.
     *
     * <p>⚠ The refusals are deliberate and each names what to do — see {@code OpeningBalanceService}. A
     * migration that fails with "invalid request" is a migration nobody can finish.
     */
    @PostMapping("/postOpeningBalance")
    @PreAuthorize(OWNER_ONLY)
    public GenericResponse post(@RequestParam(name = "customerId", required = false) Long customerId,
                                @RequestParam(name = "venderId", required = false) Long venderId,
                                @RequestParam("amount") BigDecimal amount,
                                @RequestParam(name = "reference", required = false) String reference,
                                @RequestParam(name = "idempotencyKey", required = false) String idempotencyKey) {
        try {
            if (customerId == null && venderId == null) {
                return new GenericResponse("FAILED", "Choose a customer or a supplier.");
            }
            if (customerId != null && venderId != null) {
                // Not pedantry: one document cannot be both a receivable and a payable, and guessing which
                // the caller meant would post to the wrong side of the ledger.
                return new GenericResponse("FAILED", "An opening balance is for a customer OR a supplier, "
                        + "not both.");
            }
            return new GenericResponse("SUCCESS", "posted", customerId != null
                    ? openingBalanceService.postCustomerOpening(orgId(), userId(), customerId, amount,
                            reference, idempotencyKey)
                    : openingBalanceService.postVendorOpening(orgId(), userId(), venderId, amount,
                            reference, idempotencyKey));
        } catch (com.myplus.common.web.exception.ValidationException refused) {
            // A refusal is an ANSWER, not a fault: it names the cutover date, the lock or the part-paid rule
            // so the operator can act. Logged at INFO for the same reason.
            LOGGER.info("openingBalance refused: {}", refused.getMessage());
            return new GenericResponse("FAILED", refused.getMessage());
        } catch (Exception e) {
            LOGGER.error("postOpeningBalance failed", e);
            return new GenericResponse("FAILED", "The opening balance could not be recorded.");
        }
    }

    /**
     * Undo an opening balance entered wrongly.
     *
     * <p>⚠ Refuses a PARTIALLY PAID document by design — a receipt is already allocated against it and
     * posted, so a full reversal would leave that receipt pointing at nothing. See the service.
     */
    @PostMapping("/reverseOpeningBalance")
    @PreAuthorize(OWNER_ONLY)
    public GenericResponse reverse(@RequestParam("invoiceNo") String invoiceNo,
                                   @RequestParam(name = "reason", required = false) String reason) {
        try {
            return new GenericResponse("SUCCESS", "reversed",
                    openingBalanceService.reverseCustomerOpening(orgId(), invoiceNo, reason));
        } catch (com.myplus.common.web.exception.ValidationException refused) {
            LOGGER.info("openingBalance reversal refused: {}", refused.getMessage());
            return new GenericResponse("FAILED", refused.getMessage());
        } catch (Exception e) {
            LOGGER.error("reverseOpeningBalance failed", e);
            return new GenericResponse("FAILED", "The opening balance could not be reversed.");
        }
    }
}
