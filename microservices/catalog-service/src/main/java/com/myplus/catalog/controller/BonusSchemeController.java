package com.myplus.catalog.controller;

import com.myplus.catalog.entity.BonusSchemeEntity;
import com.myplus.catalog.repository.BonusSchemeRepository;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Bonus / free-goods schemes (task #17 P1). Design: {@code microservices/docs/slices/bonus-schemes.md}.
 *
 * <p>Sits beside {@code PriceRuleController} and follows it exactly: the catalog owns rule DATA and rule
 * RESOLUTION, so purchasing, the till and the storefront cannot answer "what bonus applies here?" differently.
 *
 * <p><b>Authoring is privileged.</b> A scheme gives away stock, so creating one is an admin action, not
 * counter work — the same bar {@code PriceRuleController} sets for prices.
 */
@RestController
@RequestMapping("/api/catalog/bonus-schemes")
@RequiredArgsConstructor
public class BonusSchemeController {

    private final BonusSchemeRepository repo;

    private static final List<String> SCOPES = List.of("VENDOR", "CUSTOMER", "CUSTOMER_TYPE");
    private static final List<String> BONUS_TYPES = List.of("INCLUSIVE", "EXCLUSIVE");
    private static final List<String> MODES = List.of("ONE_TIME", "REPEATING");
    private static final List<String> STATUSES = List.of("DRAFT", "ACTIVE", "EXPIRED", "DISABLED");

    /**
     * Every scheme for this tenant, newest-priority first.
     *
     * @param activeOnly when true, only schemes that are ACTIVE **and** inside their date window today. An
     *                   expired offer that still resolved would give away stock the shop is not being paid
     *                   for, silently — so "live" is evaluated here rather than left to the caller.
     */
    @GetMapping
    public ApiResponse<List<BonusSchemeEntity>> list(
            @RequestParam(name = "activeOnly", required = false, defaultValue = "false") boolean activeOnly) {
        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();
        List<BonusSchemeEntity> rows = activeOnly
                ? repo.findActiveScoped(orgId, userId).stream()
                        .filter(b -> b.isLive(LocalDate.now())).toList()
                : repo.findScoped(orgId, userId);
        return ApiResponse.success(rows, "Bonus schemes loaded");
    }

    /**
     * Create a scheme.
     *
     * <p><b>The two mandatory fields are enforced HERE, not left to the database.</b> A scheme without a bonus
     * type or a qualification mode is not a partially-filled record — it is an unanswerable one: "10+1" means
     * a different delivered quantity, a different invoice, a different cost and a different tax treatment
     * depending on which it is, and the partial-return clawback cannot recompute entitlement without the mode.
     * Refusing at the boundary is what stops an ambiguous rule reaching the resolver.
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public ApiResponse<BonusSchemeEntity> create(@RequestBody BonusSchemeEntity dto) {
        String bad = validate(dto);
        if (bad != null) return ApiResponse.error(bad, 400);

        Long orgId = CurrentUser.organizationId();
        Long userId = CurrentUser.userId();

        // Codes identify the offer on a receipt and in a report, so a duplicate makes "which one applied?"
        // unanswerable. Checked here for a friendly message; the unique index is what actually guarantees it.
        if (repo.findByCodeScoped(dto.getCode().trim(), orgId, userId).isPresent())
            return ApiResponse.error("A scheme with code " + dto.getCode().trim() + " already exists.", 400);

        dto.setId(null);
        dto.setCode(dto.getCode().trim());
        dto.setOrganizationId(orgId);
        dto.setUserId(userId);
        if (dto.getTriggerTarget() == null) dto.setTriggerTarget("PRODUCT");
        if (dto.getStatus() == null) dto.setStatus("ACTIVE");
        if (dto.getStackable() == null) dto.setStackable(Boolean.FALSE);
        dto.setCreatedAt(LocalDateTime.now());
        dto.setUpdatedAt(LocalDateTime.now());
        return ApiResponse.success(repo.save(dto), "Bonus scheme created");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public ApiResponse<BonusSchemeEntity> update(@PathVariable Long id, @RequestBody BonusSchemeEntity dto) {
        String bad = validate(dto);
        if (bad != null) return ApiResponse.error(bad, 400);

        // Anti-IDOR: by id AND scope. A scheme id from another tenant must read as absent, not as forbidden.
        var existing = repo.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId());
        if (existing.isEmpty()) return ApiResponse.error("Bonus scheme not found.", 400);

        BonusSchemeEntity e = existing.get();
        e.setCode(dto.getCode().trim());
        e.setScope(dto.getScope());
        e.setVendorId(dto.getVendorId());
        e.setCustomerId(dto.getCustomerId());
        e.setCustomerType(dto.getCustomerType());
        e.setTriggerTarget(dto.getTriggerTarget() != null ? dto.getTriggerTarget() : "PRODUCT");
        e.setTriggerProductId(dto.getTriggerProductId());
        e.setTriggerCategoryId(dto.getTriggerCategoryId());
        e.setRewardProductId(dto.getRewardProductId());
        e.setPaidQuantity(dto.getPaidQuantity());
        e.setBonusQuantity(dto.getBonusQuantity());
        e.setBonusType(dto.getBonusType());
        e.setQualificationMode(dto.getQualificationMode());
        e.setPriority(dto.getPriority());
        e.setStackable(dto.getStackable() != null ? dto.getStackable() : Boolean.FALSE);
        e.setStatus(dto.getStatus() != null ? dto.getStatus() : "ACTIVE");
        e.setStartsOn(dto.getStartsOn());
        e.setEndsOn(dto.getEndsOn());
        e.setUpdatedAt(LocalDateTime.now());
        return ApiResponse.success(repo.save(e), "Bonus scheme updated");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_PRIVILEGE')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        var existing = repo.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId());
        if (existing.isEmpty()) return ApiResponse.error("Bonus scheme not found.", 400);
        repo.delete(existing.get());
        return ApiResponse.success(null, "Bonus scheme deleted");
    }

    /**
     * What a given paid quantity earns — the arithmetic, exposed so the till, purchasing and the return
     * clawback all ask the SAME question of the SAME code rather than each re-deriving it.
     *
     * <p>Stateless on purpose: it answers for the numbers supplied, so a screen can preview an offer the
     * operator is still typing, before any scheme is saved.
     */
    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestBody Map<String, Object> body) {
        BigDecimal paid = num(body.get("quantity"));
        if (paid == null) paid = num(body.get("paidQuantity"));
        if (paid == null) return ApiResponse.error("A quantity is required.", 400);

        String mode = str(body.get("qualificationMode"));
        if (mode == null) return ApiResponse.error("A qualification mode (ONE_TIME or REPEATING) is required.", 400);

        BonusSchemeEntity probe = BonusSchemeEntity.builder()
                .paidQuantity(num(body.get("paidQuantity")))
                .bonusQuantity(num(body.get("bonusQuantity")))
                .qualificationMode(mode)
                .build();

        return ApiResponse.success(
                Map.of("paidQuantity", paid, "bonusQuantity", probe.bonusFor(paid)),
                "Preview");
    }

    private static BigDecimal num(Object v) {
        if (v == null) return null;
        try { return new BigDecimal(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static String str(Object v) {
        String s = (v == null) ? null : String.valueOf(v).trim();
        return (s == null || s.isEmpty() || "null".equals(s)) ? null : s;
    }

    /** One place for the rules, so create and update cannot diverge on what a valid scheme is. */
    private String validate(BonusSchemeEntity d) {
        if (d == null) return "No scheme supplied.";
        if (d.getCode() == null || d.getCode().trim().isEmpty()) return "A scheme code is required.";
        if (d.getScope() == null || !SCOPES.contains(d.getScope()))
            return "Scope must be one of " + SCOPES + ".";
        // MANDATORY — see the class javadoc. "10+1" is unanswerable without these two.
        if (d.getBonusType() == null || !BONUS_TYPES.contains(d.getBonusType().toUpperCase()))
            return "Bonus type is required and must be INCLUSIVE or EXCLUSIVE.";
        if (d.getQualificationMode() == null || !MODES.contains(d.getQualificationMode().toUpperCase()))
            return "Qualification mode is required and must be ONE_TIME or REPEATING.";
        if (d.getPaidQuantity() == null || d.getPaidQuantity().signum() <= 0)
            return "Paid quantity must be greater than zero.";
        if (d.getBonusQuantity() == null || d.getBonusQuantity().signum() <= 0)
            return "Bonus quantity must be greater than zero.";
        if (d.getStatus() != null && !STATUSES.contains(d.getStatus().toUpperCase()))
            return "Status must be one of " + STATUSES + ".";
        if (d.getStartsOn() != null && d.getEndsOn() != null && d.getEndsOn().isBefore(d.getStartsOn()))
            return "The end date cannot be before the start date.";
        return null;
    }
}
