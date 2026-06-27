package com.myplus.marketplace.service;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.dto.CouponDTO;
import com.myplus.marketplace.entity.Coupon;
import com.myplus.marketplace.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Store promo codes (slice 72, E13). Back-office create/list + the checkout-time validate/compute. A discount is
 * PERCENT (value 0–100) or FIXED off the subtotal, capped at subtotal. Invalid codes don't fail checkout — they yield
 * a zero discount + a message the UI can show.
 */
@Service
@RequiredArgsConstructor
public class CouponService {

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CouponRepository repo;

    /** Result of applying a code to a subtotal. {@code discount} is 0 when there is no/invalid code; {@code message}
     *  explains an invalid code (null when fine); {@code couponId} lets the caller record usage on a real order. */
    public record CouponResult(BigDecimal discount, String code, String message, Long couponId) {
        static CouponResult none() { return new CouponResult(BigDecimal.ZERO, null, null, null); }
        static CouponResult invalid(String code, String message) { return new CouponResult(BigDecimal.ZERO, code, message, null); }
    }

    @Transactional
    public CouponDTO create(CouponDTO dto, Long orgId) {
        if (dto.getCode() == null || dto.getCode().isBlank()) throw new ValidationException("Coupon code is required");
        String type = dto.getType() == null ? "" : dto.getType().trim().toUpperCase();
        if (!type.equals("PERCENT") && !type.equals("FIXED")) throw new ValidationException("Type must be PERCENT or FIXED");
        if (dto.getValue() == null || dto.getValue().signum() <= 0) throw new ValidationException("Value must be positive");

        String code = dto.getCode().trim().toUpperCase();
        repo.findByOrganizationIdAndCode(orgId, code).ifPresent(c -> {
            throw new ValidationException("A coupon with that code already exists");
        });
        Coupon c = Coupon.builder()
                .organizationId(orgId).code(code).type(type).value(dto.getValue())
                .minSpend(dto.getMinSpend())
                .active(dto.getActive() == null ? Boolean.TRUE : dto.getActive())
                .startsAt(dto.getStartsAt()).endsAt(dto.getEndsAt())
                .usageLimit(dto.getUsageLimit()).usedCount(0)
                .build();
        return toDTO(repo.save(c));
    }

    @Transactional(readOnly = true)
    public List<CouponDTO> list(Long orgId) {
        return repo.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream().map(this::toDTO).collect(Collectors.toList());
    }

    /** Validate a code against a subtotal and compute the discount (read-only — no usage recorded). */
    @Transactional(readOnly = true)
    public CouponResult validateAndCompute(Long orgId, String code, BigDecimal subtotal) {
        if (code == null || code.isBlank()) return CouponResult.none();
        String norm = code.trim().toUpperCase();
        Coupon c = repo.findByOrganizationIdAndCode(orgId, norm).orElse(null);
        if (c == null) return CouponResult.invalid(norm, "Invalid coupon code");
        if (!Boolean.TRUE.equals(c.getActive())) return CouponResult.invalid(norm, "This coupon is not active");

        LocalDateTime now = LocalDateTime.now();
        if (c.getStartsAt() != null && now.isBefore(c.getStartsAt())) return CouponResult.invalid(norm, "This coupon is not yet valid");
        if (c.getEndsAt() != null && now.isAfter(c.getEndsAt())) return CouponResult.invalid(norm, "This coupon has expired");
        if (c.getUsageLimit() != null && c.getUsedCount() != null && c.getUsedCount() >= c.getUsageLimit())
            return CouponResult.invalid(norm, "This coupon has reached its usage limit");
        BigDecimal sub = subtotal != null ? subtotal : BigDecimal.ZERO;
        if (c.getMinSpend() != null && sub.compareTo(c.getMinSpend()) < 0)
            return CouponResult.invalid(norm, "Spend at least " + c.getMinSpend() + " to use this coupon");

        BigDecimal discount;
        if ("PERCENT".equals(c.getType())) {
            discount = sub.multiply(c.getValue()).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
        } else {
            discount = c.getValue();
        }
        if (discount.compareTo(sub) > 0) discount = sub;            // never exceed the subtotal
        return new CouponResult(discount.setScale(SCALE, RoundingMode.HALF_UP), norm, null, c.getId());
    }

    /** Record one use of a coupon (called when an order actually applies it). */
    @Transactional
    public void recordUse(Long couponId) {
        if (couponId == null) return;
        repo.findById(couponId).ifPresent(c -> {
            c.setUsedCount((c.getUsedCount() == null ? 0 : c.getUsedCount()) + 1);
            repo.save(c);
        });
    }

    private CouponDTO toDTO(Coupon c) {
        return CouponDTO.builder()
                .id(c.getId()).code(c.getCode()).type(c.getType()).value(c.getValue())
                .minSpend(c.getMinSpend()).active(c.getActive())
                .startsAt(c.getStartsAt()).endsAt(c.getEndsAt())
                .usageLimit(c.getUsageLimit()).usedCount(c.getUsedCount())
                .build();
    }
}
