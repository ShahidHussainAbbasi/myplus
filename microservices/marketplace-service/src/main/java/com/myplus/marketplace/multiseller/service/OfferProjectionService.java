package com.myplus.marketplace.multiseller.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.myplus.commerce.contracts.client.InventoryClient;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Approval;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.Regulated;
import com.myplus.marketplace.multiseller.domain.MarketplaceStatus.SellerAccount;
import com.myplus.marketplace.multiseller.entity.MarketplaceOffer;
import com.myplus.marketplace.multiseller.entity.MarketplaceOfferProjection;
import com.myplus.marketplace.multiseller.entity.MarketplacePolicy;
import com.myplus.marketplace.multiseller.entity.MarketplaceProduct;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferProjectionRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceOfferRepository;
import com.myplus.marketplace.multiseller.repository.MarketplacePolicyRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceProductRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;
import com.myplus.marketplace.support.AsOrg;

import lombok.RequiredArgsConstructor;

/**
 * MKT-1c — the ONLY writer of {@code mkt_offer_projection} (K4: one writer, listed).
 *
 * <h3>Two kinds of write, deliberately separated</h3>
 * <ol>
 *   <li>{@link #publish} — in the caller's transaction: copies the offer's terms and decides LIVE or HIDDEN from
 *       facts this service owns (offer approved and not paused, seller APPROVED, product APPROVED and not regulated
 *       in Phase 1). It never calls another service.</li>
 *   <li>{@link #syncStock} — after commit, in its own transaction: asks inventory for the seller's SELLABLE
 *       quantity, as the seller's org ({@link AsOrg}). A failure leaves the previous figure and its timestamp, so
 *       an offer whose stock cannot be confirmed goes STALE and drops out of ranking (OfferEligibility) rather than
 *       showing stock nobody vouched for. A brand-new LIVE row has no timestamp at all and is not shown until its
 *       first sync — the safe default.</li>
 * </ol>
 * The projection is advisory (source §18 stage 1). The reservation at checkout (MKT-1e) is the authority.
 */
@Service
@RequiredArgsConstructor
public class OfferProjectionService {

    private static final Logger LOG = LoggerFactory.getLogger(OfferProjectionService.class);
    static final int SYNC_BATCH = 100;

    private final MarketplaceOfferProjectionRepository projections;
    private final MarketplaceOfferRepository offers;
    private final MarketplaceProductRepository products;
    private final MarketplaceSellerAccountRepository accounts;
    private final MarketplacePolicyRepository policies;
    private final InventoryClient inventory;
    private final ApplicationEventPublisher events;
    private final PlatformTransactionManager txManager;

    /** Re-derive one offer's published row. Call inside the transaction that changed the offer, seller or product. */
    @Transactional
    public MarketplaceOfferProjection publish(MarketplaceOffer offer) {
        MarketplaceOfferProjection p = projections.findById(offer.getId()).orElseGet(MarketplaceOfferProjection::new);
        MarketplaceProduct product = products.findById(offer.getMktProductId()).orElse(null);
        MarketplaceSellerAccount seller = accounts.findByOrganizationId(offer.getOrganizationId()).orElse(null);
        p.setOfferId(offer.getId());
        p.setMktProductId(offer.getMktProductId());
        p.setSellerOrganizationId(offer.getSellerOrganizationId());
        // MKT-3a: platform stock is sold and shipped by MaxTheService, whatever the warehouse organisation is called
        p.setSellerDisplayName(com.myplus.marketplace.multiseller.domain.StockSourceType.PLATFORM.name().equals(offer.getStockSourceType())
                ? PlatformWarehouseService.NAME : seller == null ? "" : seller.getDisplayName());
        p.setStockSourceType(offer.getStockSourceType());
        p.setRegulatedStatus(product == null ? Regulated.NONE.name() : product.getRegulatedStatus());
        p.setPrice(offer.getMarketplacePrice());
        p.setDeliveryAreas(offer.getDeliveryAreas());
        p.setPromiseHours(offer.getPromiseHours());
        MarketplacePolicy w = offer.getWarrantyPolicyId() == null ? null : policies.findById(offer.getWarrantyPolicyId()).orElse(null);
        p.setWarrantyMonths(w == null ? null : w.getWarrantyMonths());
        p.setWarrantyProvider(w == null ? null : w.getWarrantyProvider());
        p.setWarrantyStarts(w == null ? null : w.getWarrantyStarts());
        p.setWarrantyCovers(w == null ? null : w.getWarrantyCovers());
        p.setWarrantyExcludes(w == null ? null : w.getWarrantyExcludes());
        MarketplacePolicy r = offer.getReturnPolicyId() == null ? null : policies.findById(offer.getReturnPolicyId()).orElse(null);
        p.setReturnDays(r == null ? null : r.getReturnDays());
        boolean live = Approval.APPROVED.name().equals(offer.getApprovalStatus())
                && !Boolean.TRUE.equals(offer.getPaused())
                && seller != null && SellerAccount.APPROVED.name().equals(seller.getStatus())
                && product != null && Approval.APPROVED.name().equals(product.getApprovalStatus())
                && Regulated.NONE.name().equals(product.getRegulatedStatus());
        p.setStatus(live ? MarketplaceOfferProjection.LIVE : MarketplaceOfferProjection.HIDDEN);
        p.setUpdatedAt(LocalDateTime.now());
        MarketplaceOfferProjection saved = projections.save(p);
        if (live) events.publishEvent(new OfferChanged(offer.getId()));
        return saved;
    }

    /** A seller's account changed (approved, suspended, reinstated): every offer of that seller is re-published. */
    @Transactional
    public void publishSeller(Long sellerOrganizationId) {
        for (MarketplaceOffer o : offers.findByOrganizationId(sellerOrganizationId)) publish(o);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOfferChanged(OfferChanged e) {
        try {
            syncStock(e.offerId());
        } catch (RuntimeException ex) {
            // best effort by design: the row stays without a fresh timestamp and is treated as stale
            LOG.warn("MKT stock sync for offer {} failed ({}: {}); it stays unconfirmed until the next sweep",
                    e.offerId(), ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    /**
     * Confirm one LIVE row's sellable quantity with inventory, as the seller.
     *
     * <h3>Programmatic REQUIRES_NEW, on purpose</h3>
     * This runs from an AFTER_COMMIT listener, where data access would otherwise join the finished transaction and
     * never commit; and it is reached by a self-call, which bypasses a {@code @Transactional} proxy (the trap the
     * caching standard records for {@code @Cacheable}). So the two short transactions are explicit, and the remote
     * call sits BETWEEN them — never inside one, so a slow inventory never holds a database connection.
     */
    public void syncStock(Long offerId) {
        long[] target = tx().execute(s -> {
            MarketplaceOfferProjection p = projections.findById(offerId).orElse(null);
            if (p == null || !MarketplaceOfferProjection.LIVE.equals(p.getStatus())) return null;
            MarketplaceOffer o = offers.findById(offerId).orElse(null);
            return o == null ? null : new long[] {o.getOrganizationId(), o.getSourceProductId()};
        });
        if (target == null) return;
        Map<String, Float> detail = AsOrg.call(target[0], () -> inventory.getSellableDetail(target[1]));
        Float sellable = detail == null ? null : detail.get("sellable");
        if (sellable == null) return;   // no answer is not "zero": leave it unconfirmed
        tx().executeWithoutResult(s -> projections.findById(offerId).ifPresent(p -> {
            p.setAvailableQty(BigDecimal.valueOf(Math.max(0f, sellable)));
            p.setLastSyncAt(LocalDateTime.now());
            projections.save(p);
        }));
    }

    private TransactionTemplate tx() {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    /**
     * The sweeper: re-confirm the LIVE rows confirmed longest ago, bounded per run. Each row is its own
     * transaction, so one seller's inventory outage cannot stop the rest.
     */
    @Scheduled(fixedDelayString = "${mkt.projection.sync-ms:300000}", initialDelayString = "${mkt.projection.sync-initial-ms:120000}")
    public void sweep() {
        List<MarketplaceOfferProjection> due = projections.findByStatusOrderByLastSyncAtAsc(
                MarketplaceOfferProjection.LIVE, PageRequest.of(0, SYNC_BATCH));
        for (MarketplaceOfferProjection p : due) onOfferChanged(new OfferChanged(p.getOfferId()));
    }
}
