package com.myplus.marketplace.multiseller.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MKT-1g — the settlement statement, the ledger and payouts on the wire.
 *
 * <p>Every statement line carries the whole split, so the seller can add it up:
 * {@code customerAmount = payable + commission + delivery + fees + tax + reserve + adjustment} (source §15).
 */
public final class SettlementDTOs {

    private SettlementDTOs() {
    }

    /**
     * One order line on the seller's statement. {@code collectedBy}: SELLER (cash on delivery, the rider holds it) or
     * PLATFORM (paid online). {@code eligibleOn}: the day the line becomes payable (T+N business days after the return
     * window closes); null until delivered.
     */
    public record StatementLine(Long id, String orderNo, String productName, Integer quantity, String status,
            LocalDateTime deliveredAt, LocalDate eligibleOn, String collectedBy, BigDecimal customerAmount,
            BigDecimal commission, BigDecimal delivery, BigDecimal fees, BigDecimal tax, BigDecimal reserve,
            BigDecimal adjustment, BigDecimal payable, String payoutNo) {
    }

    /** One immutable ledger row. Signed from the seller's side: credit = owed to the seller. */
    public record EntryRow(Long id, String entryType, String ref, String memo, BigDecimal debit, BigDecimal credit,
            LocalDateTime effectiveAt) {
    }

    /** The seller's account: what MaxTheService owes it now (negative = it owes MaxTheService), and its rows. */
    public record AccountView(Long organizationId, String sellerName, BigDecimal balance, PayoutView openPayout,
            List<EntryRow> entries, List<PayoutView> payouts, CodStanding cod) {
    }

    /**
     * MKT-2d — what the seller owes MaxTheService for cash orders, and since when. {@code owed} is the negative balance
     * (zero when the seller is owed money or square); {@code payBy} = owedSince + the operator's days to pay;
     * {@code overdue} once today is after it; {@code codStopped} when the operator's switch stops its cash orders.
     */
    public record CodStanding(BigDecimal owed, LocalDate owedSince, LocalDate payBy, boolean overdue, boolean codStopped) {
    }

    /** MKT-2d — one seller on the operator's cash-order reconciliation. */
    public record CodRow(Long organizationId, String sellerName, BigDecimal cashCollected, BigDecimal remitted,
            BigDecimal balance, CodStanding standing) {
    }

    /** MKT-2d — money the seller paid MaxTheService. {@code note} is required when it is less than owed. */
    public record RemittanceRequest(Long organizationId, BigDecimal amount, String reference, String note,
            String idempotencyKey) {
    }

    /** The operator's list of sellers with a ledger. */
    public record AccountRow(Long organizationId, String sellerName, BigDecimal balance, PayoutView openPayout) {
    }

    public record PayoutView(Long id, String payoutNo, Long organizationId, String sellerName, BigDecimal requestedAmount,
            BigDecimal approvedAmount, String status, String bankReference, LocalDateTime requestedAt,
            LocalDateTime approvedAt, LocalDateTime paidAt, boolean requestedByMe, Integer version) {
    }

    public record PayoutRequest(Long organizationId, String idempotencyKey) {
    }

    public record PayoutDecision(Long id, String bankReference) {
    }

    /** {@code amount} is signed from the seller's side: +100 credits the seller, −100 takes it back. */
    public record AdjustmentRequest(Long organizationId, BigDecimal amount, String reason, String idempotencyKey) {
    }

    /** What one settlement run did. {@code waitingForBooks}: due lines held because no books are chosen yet. */
    public record RunResult(int checked, int settled, int waiting, int onHold, int waitingForBooks) {
    }

    /** The operator's settlement settings: T+N, and whose ledger takes the commission. */
    public record SettingsView(int tPlusDays, Long booksOrganizationId, boolean booksAreMine, int codRemitDays,
            boolean codStopWhenOverdue) {
    }

    /** A field not sent is left as it is. MKT-2d added the two cash-order settings. */
    public record SettingsRequest(Integer tPlusDays, Boolean useMyBooks, Integer codRemitDays, Boolean codStopWhenOverdue) {

        public SettingsRequest(Integer tPlusDays, Boolean useMyBooks) {
            this(tPlusDays, useMyBooks, null, null);
        }
    }
}
