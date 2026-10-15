package com.myplus.business_service.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.business_service.dto.PurchaseDTO;
import com.myplus.business_service.dto.StockDTO;
import com.myplus.business_service.entity.Purchase;
import com.myplus.business_service.entity.Vender;
import com.myplus.business_service.repository.VenderRepo;
import com.myplus.business_service.service.IPurchaseService;
import com.myplus.business_service.util.RequestUtil;
import com.myplus.commerce.contracts.dto.PurchaseRecordRequest;
import com.myplus.commerce.contracts.dto.PurchaseRecordResult;
import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.web.exception.ValidationException;

/**
 * MKT-3c — the ONE way a service puts goods one tenant BUYS from another into the buyer's books. The mirror of
 * {@link InternalSalesController#recordSale}: the marketplace records the seller's sale there and the warehouse's
 * purchase here, so neither side of a stock transfer has money logic of its own.
 *
 * <h3>No money logic here</h3>
 * {@code PurchaseService.addPurchase} already does the whole job (stock in, serials registered, the payable, input
 * tax, the GL event, the cost stamp, the period lock, idempotency on the key). This maps the wire request onto the DTO
 * that path takes. The only figure set here is the line value, quantity × unit cost, which is what the purchase form
 * itself posts as {@code totalAmount}.
 *
 * <h3>Owed, never paid</h3>
 * {@code paidAmount} is set to zero on purpose: left null, the purchase path treats the bill as a cash purchase paid
 * in full. A transfer is bought on account from the supplier, who is paid through payables like any other.
 *
 * <h3>Trust boundary</h3>
 * Reachable only inside the private network, with a forwarded identity (X-Org-Id/X-User-Id/X-Internal-Secret). The
 * org in the BODY must be the AUTHENTICATED org, so a caller can only book a purchase into the tenant it acts as.
 */
@RestController
@RequestMapping("/internal/purchases")
public class InternalPurchasesController {

    private static final Logger LOG = LoggerFactory.getLogger(InternalPurchasesController.class);

    @Autowired
    private IPurchaseService purchaseService;

    @Autowired
    private VenderRepo venderRepo;

    @Autowired
    private RequestUtil requestUtil;

    @Autowired
    private com.myplus.common.settings.CapabilityService capabilityService;

    @PostMapping
    public ResponseEntity<?> recordPurchase(@RequestBody PurchaseRecordRequest request) throws Exception {
        if (request == null) throw new ValidationException("A purchase request is required");
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank())
            throw new ValidationException("idempotencyKey is required — it is what makes a retry safe");
        if (request.getProductId() == null) throw new ValidationException("Choose the product.");
        if (request.getQuantity() == null || request.getQuantity().signum() <= 0)
            throw new ValidationException("Enter how many were received.");
        if (request.getUnitCost() == null || request.getUnitCost().signum() <= 0)
            throw new ValidationException("Enter the cost of one unit.");
        if (request.getVendorName() == null || request.getVendorName().isBlank())
            throw new ValidationException("Name the supplier.");

        AuthenticatedUser user = requestUtil.getCurrentUser();
        Long org = (user == null) ? null : user.getOrganizationId();
        if (org == null) throw new ValidationException("No tenant identity on the request");
        // Anti-IDOR: the body may not name a different tenant than the one it authenticated as.
        if (request.getOrganizationId() != null && !request.getOrganizationId().equals(org))
            throw new ValidationException("Organization mismatch: a purchase may only be recorded for its own tenant");

        // The purchase path refuses serials without the capability as "This is not switched on for your business.", which
        // names nothing; the person receiving a box of phones needs to be told WHICH setting it is.
        if (request.getSerials() != null && !request.getSerials().isBlank()
                && !capabilityService.isEnabled(com.myplus.common.settings.Capability.SERIAL_TRACKING))
            throw new ValidationException("Serial / IMEI tracking is not switched on for your business, so units with serial numbers cannot be received.");

        Vender vendor = vendorNamed(org, user.getUserId(), request.getVendorName().trim());

        PurchaseDTO dto = new PurchaseDTO();
        dto.setIdempotencyKey(request.getIdempotencyKey());
        dto.setProductId(request.getProductId());
        dto.setQuantity(request.getQuantity());
        dto.setVenderId(vendor.getId());
        dto.setVenderName(vendor.getName());
        dto.setPaidAmount(BigDecimal.ZERO);                 // owed in full: see the class note
        dto.setTotalAmount(request.getQuantity().multiply(request.getUnitCost()).setScale(2, RoundingMode.HALF_UP));
        dto.setPurchaseInvoiceNo(request.getBillNo());
        dto.setSerials(request.getSerials());
        dto.setSerialsSubmitted(request.getSerials() != null);
        StockDTO stock = new StockDTO();
        stock.setBpurchaseRate(request.getUnitCost());     // the selling price stays the product's own (bsellRate 0)
        dto.setStock(stock);

        Purchase saved;
        try {
            saved = purchaseService.addPurchase(dto);
        } catch (com.myplus.business_service.service.BusinessRuleException refused) {
            // a rule of the purchase path ("Enter the expiry date for …"): the caller's person must read the sentence,
            // so it travels as a 400 with that message, never as a 500 "Internal server error"
            throw new ValidationException(refused.getMessage());
        }
        if (saved == null) throw new ValidationException("The purchase could not be recorded. Please try again.");
        LOG.info("MKT-3c: recorded purchase {} for org {} from {} (key {})", saved.getPurchaseId(), org, vendor.getName(),
                request.getIdempotencyKey());
        return ResponseEntity.ok(PurchaseRecordResult.builder()
                .purchaseId(saved.getPurchaseId())
                .vendorId(vendor.getId())
                .totalAmount(saved.getTotalAmount())
                .build());
    }

    /** The supplier of that name in this tenant (case-insensitive, as {@code /addVender} checks), or a new one. */
    Vender vendorNamed(Long org, Long userId, String name) {
        return venderRepo.findScoped(org, userId).stream()
                .filter(v -> v.getName() != null && v.getName().trim().equalsIgnoreCase(name))
                .findFirst()
                .orElseGet(() -> {
                    Vender v = new Vender();
                    LocalDateTime now = LocalDateTime.now();
                    v.setName(name);
                    v.setOrganizationId(org);
                    v.setUserId(userId);
                    v.setDated(now);
                    v.setUpdated(now);
                    v.setDueAmount(BigDecimal.ZERO);
                    v.setPayableOtherOpen(BigDecimal.ZERO);
                    v.setPayableAdvance(BigDecimal.ZERO);
                    v.setPayableStampVersion(0L);
                    return venderRepo.save(v);
                });
    }
}
