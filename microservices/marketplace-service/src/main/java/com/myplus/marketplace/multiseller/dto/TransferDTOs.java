package com.myplus.marketplace.multiseller.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MKT-3c — stock a seller sends to the MaxTheService warehouse, which MaxTheService buys; MKT-5b — or consigns to it
 * under an active agreement, keeping ownership ({@code kind} BUY | CONSIGN).
 */
public final class TransferDTOs {

    private TransferDTOs() { }

    /** A product the seller sells that MaxTheService also carries: the only ones it can send. */
    public record Product(Long mktProductId, String name) { }

    /** {@code kind} BUY (null, the 3c default) or CONSIGN; for CONSIGN {@code unitPrice} is the value a unit declared. */
    public record OfferRequest(Long mktProductId, Integer quantity, BigDecimal unitPrice, String note, String kind) {
        public OfferRequest(Long mktProductId, Integer quantity, BigDecimal unitPrice, String note) {
            this(mktProductId, quantity, unitPrice, note, null);
        }
    }

    /** Cancel (the seller), Reject with a reason or Receive with the serials (the warehouse). */
    public record DecisionRequest(String transferNo, Integer version, String reason, List<String> serials) { }

    public record Transfer(String transferNo, String productName, int quantity, BigDecimal unitPrice, BigDecimal total,
                           String note, String status, String rejectReason, String sellerName, boolean serialRequired,
                           String saleInvoiceNo, Long purchaseId, LocalDateTime createdAt, LocalDateTime decidedAt,
                           Integer version, String kind, String agreementNo) { }

    /**
     * MKT-5b — consigned stock the warehouse holds: per owner and product, the units and their declared value. MKT-5c —
     * the units sold so far, whether the caller is the party its agreement lets set the price ({@code canList}), and the
     * warehouse's listing of it, if any.
     */
    public record Held(String consignorName, Long mktProductId, String productName, long units, BigDecimal value,
                       Long consignorOrgId, long sold, boolean canList, Listing listing, Shrink shrink) {
        public Held(String consignorName, Long mktProductId, String productName, long units, BigDecimal value) {
            this(consignorName, mktProductId, productName, units, value, null, 0, false, null, null);
        }

        public Held(String consignorName, Long mktProductId, String productName, long units, BigDecimal value,
                    Long consignorOrgId, long sold, boolean canList, Listing listing) {
            this(consignorName, mktProductId, productName, units, value, consignorOrgId, sold, canList, listing, null);
        }

        public Held withShrink(Shrink s) {
            return new Held(consignorName, mktProductId, productName, units, value, consignorOrgId, sold, canList, listing, s);
        }
    }

    /**
     * MKT-5d — counts of one owner's product and what they found missing. {@code missing}: units still unresolved (in
     * the owner's books, never sold). {@code countDue}: the agreement's next count; {@code canCount} the caller is the
     * warehouse; {@code canClaim} the owner. The agreement's terms that decide a claim ride along for the forms.
     */
    public record Shrink(long missing, LocalDate countDue, boolean countOverdue, boolean canCount, boolean canClaim,
                         String agreementNo, BigDecimal tolerancePct, Integer claimWindowDays, boolean proofRequired,
                         String damageBearer, String expiryBearer, List<CountRow> counts) { }

    /**
     * One count. {@code open}: missing and unresolved; {@code claimable}: of those, what a claim can still ask for
     * (beyond the tolerance, not already claimed, before {@code claimBy}); {@code writable}: what the owner can write off
     * (open, not under a waiting claim).
     */
    public record CountRow(Long id, String countNo, Integer version, LocalDateTime countedAt, int expected, int counted,
                           int missing, int tolerated, int writtenOff, int approved, int open, int claimable, int writable,
                           LocalDate claimBy, String note, List<ClaimRow> claims) { }

    /** A shrinkage claim, for the owner's row and the operator's queue. */
    public record ClaimRow(Long id, String claimNo, Integer version, String countNo, Long consignorOrgId, String consignorName,
                           String productName, String kind, String responsible, int units, BigDecimal amount, String proof,
                           String note, String status, String decisionReason, String settlementRef,
                           LocalDateTime raisedAt, LocalDateTime decidedAt) { }

    /** MKT-5d — the warehouse counted one owner's product. */
    public record CountRequest(Long consignorOrgId, Long mktProductId, Integer counted, String note, String idempotencyKey) { }

    /** MKT-5d — the owner claims units of a count from the custodian. */
    public record ClaimRequest(Long countId, String kind, Integer units, String proof, String note) { }

    /**
     * MKT-5d — a decision on a count or a claim: WRITE_OFF (the owner, {@code id} = the count), WITHDRAW (the owner) or
     * APPROVE / REJECT with a reason (the operator; {@code id} = the claim).
     */
    public record ShrinkDecision(Long id, Integer version, String action, String reason) { }

    /**
     * MKT-5c — the warehouse's offer of one owner's consigned stock: its terms, whether shoppers see it ({@code live})
     * and how many units it can still promise ({@code available}: held less what open orders were promised).
     */
    public record Listing(Long offerId, Integer version, BigDecimal price, boolean live, long available, String deliveryAreas,
                          Integer promiseHours, Long warrantyPolicyId, Long returnPolicyId, String agreementNo) { }

    /**
     * MKT-5c — list consigned stock for sale, or change its terms. {@code consignorOrgId}: whose stock, when the
     * warehouse lists it (the owner's own call names none). The first listing needs every term; a change keeps what it
     * does not mention.
     */
    public record ListRequest(Long consignorOrgId, Long mktProductId, BigDecimal price, String deliveryAreas,
                              Integer promiseHours, Long warrantyPolicyId, Long returnPolicyId, Integer version) { }

    /**
     * What the screen shows. The warehouse sees what was sent to it; any other seller the products it can send and its
     * own transfers. {@code warehouseNamed} false: MaxTheService has no warehouse, so nothing can be sent.
     * {@code consignUnder}: the seller's active consignment agreement (CA-…), or null when it cannot consign.
     * {@code held}: consigned stock at the warehouse (the seller's own, or, for the warehouse, every owner's).
     */
    public record View(boolean warehouse, boolean warehouseNamed, List<Product> products, List<Transfer> transfers,
                       String consignUnder, List<Held> held) {
        public View(boolean warehouse, boolean warehouseNamed, List<Product> products, List<Transfer> transfers) {
            this(warehouse, warehouseNamed, products, transfers, null, List.of());
        }
    }
}
