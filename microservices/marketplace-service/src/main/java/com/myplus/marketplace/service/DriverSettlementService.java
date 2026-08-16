package com.myplus.marketplace.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.PaymentReceiptRequest;
import com.myplus.commerce.contracts.dto.PaymentReceiptResult;
import com.myplus.common.web.PageResponse;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.dto.DeliveryRecordDTO;
import com.myplus.marketplace.dto.DriverSettlementDTO;
import com.myplus.marketplace.entity.DeliveryRecord;
import com.myplus.marketplace.entity.DriverSettlement;
import com.myplus.marketplace.repository.DeliveryRecordRepository;
import com.myplus.marketplace.repository.DriverSettlementRepository;
import com.myplus.marketplace.support.AsOrg;

import lombok.RequiredArgsConstructor;

/**
 * OMS O7 D5 — the day-end cash-up for a driver who has no device. Closes backlog B1.
 *
 * <h3>Why this act, and not the keying, is what posts the money</h3>
 * D4 records what each shop paid Ahsan at the door. It posts nothing. That is deliberate as of D5: if the
 * receipts landed in AR at keying time, the books would already be right and the day-end count would be a
 * report nobody has to run — which is exactly B1's complaint, that <i>"marked paid" and "money in the safe" are
 * two different things nobody compares</i>. Making the posting depend on the count is what gives the count
 * teeth, and it is what makes a driver who keeps the cash <b>visible</b> (his collections sit open and age)
 * rather than invisible (AR cleared, GL says the money is in the safe).
 *
 * <p>The price, stated rather than hidden: between keying and the cash-up, the outlet's statement still shows
 * that invoice open although they paid at the door. That window is hours and closes every working day.
 *
 * <h3>Pattern: aggregate + anti-corruption layer, with claim-then-act</h3>
 * <ul>
 *   <li><b>Aggregate.</b> A settlement is the consistency boundary over a set of collections and owns the
 *       invariant <i>a collection is remitted at most once</i>. The invariant is enforced by the claim UPDATE,
 *       not by a Java check, so two admins on two browsers cannot both win.</li>
 *   <li><b>Anti-corruption layer.</b> Marketplace never speaks AR. It says "this outlet paid this much" across
 *       {@link TradeClient}; business-service decides which invoices that covers, what the receipt is called
 *       and what the journal looks like. <b>No money logic exists here.</b></li>
 *   <li><b>Claim then act.</b> Take the local lock BEFORE the remote call. Reversed, a lost race would leave
 *       receipts committed remotely against a settlement that rolled back locally.</li>
 * </ul>
 *
 * <h3>What this deliberately does not do</h3>
 * Journal the variance. It is recorded and reported and never posted, exactly as the till's Z report variance
 * is — there is no cash-with-drivers clearing account on this platform, and inventing one inside a delivery
 * feature would be a second money path. Recorded as open question Q1 of §13.8.
 */
@Service
@RequiredArgsConstructor
public class DriverSettlementService {

    private static final Logger LOG = LoggerFactory.getLogger(DriverSettlementService.class);

    /** Cash is what a driver carries. A shop paying him by cheque has nowhere to say so yet — §13.8 Q3. */
    private static final String COLLECTION_METHOD = "CASH";

    private static final int MAX_PAGE = 200;

    private final DeliveryRecordRepository deliveryRepository;
    private final DriverSettlementRepository settlementRepository;
    private final TradeClient tradeClient;

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────
    //  Reads
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>The control surface</b> — cash a driver keyed as collected and has not handed over.
     *
     * <p>A row here is a claim that somebody is holding the company's money. Oldest first, because the question
     * an admin asks of this list is "what has been sitting the longest".
     */
    @Transactional(readOnly = true)
    public PageResponse<DeliveryRecordDTO> openCollections(String driver, LocalDate from, LocalDate to,
                                                           Integer page, Integer size, Long orgId, Long userId) {
        Pageable pageable = PageRequest.of(page == null || page < 0 ? 0 : page,
                size == null || size <= 0 ? 50 : Math.min(size, MAX_PAGE));
        Page<DeliveryRecord> found = deliveryRepository.findOpenCollections(orgId, userId, blankToNull(driver),
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.atTime(23, 59, 59),
                pageable);
        return PageResponse.of(found, DeliveryRecordDTO::of);
    }

    /** The tenant's remittances, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<DriverSettlementDTO> list(String driver, LocalDate from, LocalDate to,
                                                  Integer page, Integer size, Long orgId, Long userId) {
        Pageable pageable = PageRequest.of(page == null || page < 0 ? 0 : page,
                size == null || size <= 0 ? 25 : Math.min(size, MAX_PAGE));
        return PageResponse.of(
                settlementRepository.findScoped(orgId, userId, blankToNull(driver), from, to, pageable),
                DriverSettlementDTO::of);
    }

    /**
     * One remittance and what it swept up.
     *
     * <p>Scoped by id, because the id arrives from a PATH: D2's rule is that whether a read needs scoping
     * depends on where the id came from, not on which method reads it. Another tenant's settlement reads as
     * absent, worded identically to a missing one.
     */
    @Transactional(readOnly = true)
    public DriverSettlementDTO get(Long id, Long orgId, Long userId) {
        DriverSettlement s = settlementRepository.findByIdScoped(id, orgId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Settlement not found"));
        DriverSettlementDTO dto = DriverSettlementDTO.of(s);
        List<DeliveryRecord> rows = deliveryRepository.findBySettlementIdOrderByRecordedAtAsc(s.getId());
        dto.setCollections(rows.stream().map(DeliveryRecordDTO::of).toList());
        dto.setReceipts(rows.stream().map(DeliveryRecord::getReceiptNo).filter(x -> x != null).toList());
        return dto;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────
    //  The settlement
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public DriverSettlementDTO settle(DriverSettlementDTO dto, Long orgId, Long userId, String userName) {
        if (dto == null || dto.getDeliveryIds() == null || dto.getDeliveryIds().isEmpty())
            throw new ValidationException("Choose the collections this driver is handing over.");
        if (dto.getCountedAmount() == null || dto.getCountedAmount().signum() < 0)
            throw new ValidationException("Count the cash first — a settlement without a count has settled nothing.");

        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(dto.getDeliveryIds()));
        LocalDate on = dto.getSettlementDate() != null ? dto.getSettlementDate() : LocalDate.now();

        // Re-read SCOPED and STILL-OPEN inside this transaction. The ids came from a browser, so the tenant
        // predicate belongs in the query rather than applied afterwards.
        List<DeliveryRecord> rows = deliveryRepository.findClaimable(ids, orgId, userId);
        if (rows.isEmpty())
            throw new ValidationException("None of those collections are still open — they may already have been settled.");
        if (rows.size() != ids.size())
            // Refused rather than quietly settling the remainder: the cash was counted against a set that no
            // longer exists, so the variance this would produce would be meaningless.
            throw new ValidationException((ids.size() - rows.size()) + " of " + ids.size()
                    + " collections are no longer open. Reload the list and count again.");

        String driver = oneDriverOnly(rows);
        BigDecimal declared = BigDecimal.ZERO;
        for (DeliveryRecord r : rows) {
            if (r.getCustomerId() == null)
                // Nobody to credit. Refuse rather than post the money to a guess — a receipt has to clear a
                // real account or it is not a receipt.
                throw new ValidationException("The collection on invoice "
                        + (r.getInvoiceNo() == null ? "#" + r.getId() : r.getInvoiceNo())
                        + " has no trade account behind it, so nothing can be credited.");
            declared = declared.add(nz(r.getAmountCollected()));
        }

        BigDecimal counted = dto.getCountedAmount();
        BigDecimal variance = counted.subtract(declared);      // negative = SHORT, as cashier_shift.variance is
        if (variance.signum() != 0 && isBlank(dto.getNote()))
            // The cheapest control there is, and the one B1 is about: a bag that did not match, with nobody
            // saying why, is precisely the thing that goes unnoticed.
            throw new ValidationException("The cash is " + variance.abs().toPlainString()
                    + (variance.signum() < 0 ? " SHORT" : " OVER")
                    + " of what was declared. Explain the difference in the note before settling.");

        long seq = settlementRepository.maxSeqForOrg(orgId) + 1;
        DriverSettlement header = settlementRepository.save(DriverSettlement.builder()
                .organizationId(orgId)
                .settlementSeq(seq).settlementNo("DS-" + seq)
                .driverName(driver)
                .settlementDate(on)
                .declaredAmount(declared).countedAmount(counted).varianceAmount(variance)
                .collectionCount(rows.size())
                .depositReference(dto.getDepositReference())
                .note(dto.getNote())
                .settledByUserId(userId).settledByName(userName)
                .settledAt(LocalDateTime.now())
                .build());

        // ── CLAIM, before a single rupee is sent anywhere ────────────────────────────────────────────────
        List<Long> claimIds = rows.stream().map(DeliveryRecord::getId).toList();
        int claimed = deliveryRepository.claim(claimIds, header.getId());
        if (claimed != rows.size())
            // Somebody settled one of these between the read and the write. Everything so far is local, so the
            // rollback costs nothing — which is the entire reason the claim happens before the remote calls.
            throw new ValidationException(
                    "Another settlement took some of these collections while this one was being prepared. "
                    + "Reload the list and count again.");

        // ── POST, one receipt per collection, through the contract ───────────────────────────────────────
        List<String> receipts = new ArrayList<>();
        for (DeliveryRecord r : rows) {
            // Keyed on the COLLECTION, never on this settlement. The receipt commits REMOTELY, so a failure
            // part-way through rolls the claim back locally while the earlier receipts stand; on retry a key
            // derived from the settlement would be new and would mint duplicates, while this one replays.
            // D1's dispatch key learned the same lesson (§8.1b #3).
            String key = "o7d5-" + orgId + "-" + r.getId();
            PaymentReceiptResult res;
            try {
                res = AsOrg.call(orgId, () -> tradeClient.receivePayment(PaymentReceiptRequest.builder()
                        .customerId(r.getCustomerId())
                        .amount(nz(r.getAmountCollected()))
                        .method(COLLECTION_METHOD)
                        .paidOn(on)
                        .reference(header.getSettlementNo()
                                + (r.getInvoiceNo() == null ? "" : " / " + r.getInvoiceNo()))
                        .idempotencyKey(key)
                        .build()));
            } catch (RuntimeException ex) {
                // NOT best-effort, and named so the failure is diagnosable: a remote call inside a transaction
                // is otherwise the hardest kind of error to read afterwards. All-or-nothing is the right shape
                // — a half-posted remittance would leave a variance describing a count that no longer matches
                // anything.
                LOG.error("O7 D5: receipt FAILED for collection {} (invoice {}, customer {}, org {}) — "
                        + "settlement {} rolled back", r.getId(), r.getInvoiceNo(), r.getCustomerId(), orgId,
                        header.getSettlementNo(), ex);
                throw new ValidationException("The receipt for invoice "
                        + (r.getInvoiceNo() == null ? "#" + r.getId() : r.getInvoiceNo())
                        + " was refused: " + (ex.getMessage() == null ? "no reason given" : ex.getMessage())
                        + ". Nothing has been settled.");
            }
            String receiptNo = res == null ? null : res.getReceiptNo();
            r.setSettlementId(header.getId());     // the bulk claim bypassed the persistence context
            r.setReceiptNo(receiptNo);
            deliveryRepository.save(r);
            if (receiptNo != null) receipts.add(receiptNo);
        }

        LOG.info("O7 D5: {} — driver {} on {}: {} collections, declared {}, counted {}, variance {}, receipts {}",
                header.getSettlementNo(), driver, on, rows.size(), declared, counted, variance, receipts);

        DriverSettlementDTO out = DriverSettlementDTO.of(header);
        out.setReceipts(receipts);
        out.setCollections(rows.stream().map(DeliveryRecordDTO::of).toList());
        return out;
    }

    /**
     * A remittance is ONE person handing over ONE bag of cash.
     *
     * <p>D4 defined {@code deliveredBy} as free text — "a note, not an identity and not evidence" — and a
     * settlement inherits that weakness. Refusing a mixed set contains it: two names on one row would make the
     * driver column a lie and the variance unattributable to anybody. It does not remove the weakness; making
     * drivers real identities is open question Q2 of §13.8.
     */
    private static String oneDriverOnly(List<DeliveryRecord> rows) {
        Set<String> names = new LinkedHashSet<>();
        for (DeliveryRecord r : rows) names.add(r.getDeliveredBy() == null ? "" : r.getDeliveredBy().trim());
        if (names.size() > 1)
            throw new ValidationException("These collections name more than one driver ("
                    + String.join(", ", names.stream().map(n -> n.isEmpty() ? "(unnamed)" : n).toList())
                    + "). Settle one driver at a time.");
        String only = names.iterator().next();
        return only.isEmpty() ? null : only;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private static String blankToNull(String s) { return isBlank(s) ? null : s.trim(); }
}
