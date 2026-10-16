package com.myplus.marketplace.multiseller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import com.myplus.common.web.exception.ValidationException;
import com.myplus.marketplace.multiseller.dto.ConsignmentDTOs;
import com.myplus.marketplace.multiseller.entity.MarketplaceConsignmentAgreement;
import com.myplus.marketplace.multiseller.entity.MarketplaceSellerAccount;
import com.myplus.marketplace.multiseller.repository.MarketplaceConsignmentAgreementRepository;
import com.myplus.marketplace.multiseller.repository.MarketplaceSellerAccountRepository;

/** MKT-5a — consignment agreements: offered with every term, answered by the consignor, never edited. */
@ExtendWith(MockitoExtension.class)
class ConsignmentAgreementServiceTest {

    static final long SHOP = 7L, OTHER = 8L, WAREHOUSE = 23L;

    @Mock MarketplaceConsignmentAgreementRepository agreements;
    @Mock MarketplaceSellerAccountRepository accounts;
    @Mock MarketplaceSettingsService settings;
    @Mock MarketplaceSellerService sellers;
    @Mock SellerAccess access;
    @Mock MarketplaceAuditService audit;
    ConsignmentAgreementService service;

    final List<MarketplaceConsignmentAgreement> table = new ArrayList<>();

    @BeforeEach
    void wire() {
        service = new ConsignmentAgreementService(agreements, accounts, settings, sellers, access, audit);
        MarketplaceSellerAccount a = new MarketplaceSellerAccount();
        a.setOrganizationId(SHOP);
        a.setDisplayName("Shahzad Mobile Shop");
        a.setStatus("APPROVED");
        lenient().when(accounts.findByOrganizationId(SHOP)).thenReturn(Optional.of(a));
        lenient().when(settings.warehouseOrg()).thenReturn(Optional.of(WAREHOUSE));
        lenient().when(access.org()).thenReturn(SHOP);
        lenient().when(agreements.save(any())).thenAnswer(i -> {
            MarketplaceConsignmentAgreement c = i.getArgument(0);
            if (c.getId() == null) {
                c.setId((long) table.size() + 1);
                c.setVersion(0);
                table.add(c);
            } else c.setVersion(c.getVersion() + 1);
            return c;
        });
        lenient().when(agreements.findById(anyLong())).thenAnswer(i -> table.stream()
                .filter(c -> c.getId().equals(i.getArgument(0))).findFirst());
        lenient().when(agreements.findFirstByConsignorOrgIdAndConsigneeOrgIdAndStatusOrderByIdDesc(anyLong(), anyLong(), anyString()))
                .thenAnswer(i -> table.stream().filter(c -> c.getConsignorOrgId().equals(i.getArgument(0))
                        && c.getConsigneeOrgId().equals(i.getArgument(1)) && c.getStatus().equals(i.getArgument(2)))
                        .max(Comparator.comparing(MarketplaceConsignmentAgreement::getId)));
    }

    static ConsignmentDTOs.Terms terms(String commission) {
        return new ConsignmentDTOs.Terms("on_sale", "CONSIGNOR", new BigDecimal(commission), 7, 30, 30, new BigDecimal("2"),
                "CONSIGNEE", "CONSIGNOR", "CONSIGNEE", 14, true, 180, "CONSIGNOR");
    }

    ConsignmentDTOs.Agreement offer(String commission) {
        return service.offer(new ConsignmentDTOs.OfferRequest(SHOP, terms(commission), "first terms"));
    }

    ConsignmentDTOs.Agreement answer(ConsignmentDTOs.Agreement a, String action, String reason, boolean operator) {
        return service.decide(new ConsignmentDTOs.DecisionRequest(a.id(), action, a.version(), reason), operator);
    }

    @Test
    @DisplayName("[MKT-R4.6] [MKT-R12.2] [MKT-R2.2] the operator offers every term to a seller; the consignee is the warehouse; nothing is active until the seller accepts")
    void offerHoldsEveryTerm() {
        ConsignmentDTOs.Agreement a = offer("12.5");
        assertThat(a.agreementNo()).isEqualTo("CA-000001");
        assertThat(a.status()).isEqualTo("OFFERED");
        assertThat(a.consigneeOrganizationId()).isEqualTo(WAREHOUSE);
        assertThat(a.consigneeName()).isEqualTo("MaxTheService");
        assertThat(a.terms().ownershipTransfer()).isEqualTo("ON_SALE");
        assertThat(a.terms().commissionPct()).isEqualByComparingTo("12.50");
        assertThat(service.activeFor(SHOP, WAREHOUSE)).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R4.6] a missing or out-of-range term is refused by name, and nothing is saved")
    void termsRefusedByName() {
        assertThatThrownBy(() -> offer("60")).hasMessage("Commission: enter a percentage from 0 to 50.");
        var t = terms("10");
        var noCount = new ConsignmentDTOs.Terms(t.ownershipTransfer(), t.priceSetBy(), t.commissionPct(), t.settleEveryDays(),
                t.reconcileEveryDays(), 0, t.shrinkageTolerancePct(), t.damageBearer(), t.expiryBearer(), t.insuredBy(),
                t.claimWindowDays(), t.proofRequired(), t.unsoldReturnDays(), t.unsoldReturnBearer());
        assertThatThrownBy(() -> service.offer(new ConsignmentDTOs.OfferRequest(SHOP, noCount, null)))
                .hasMessage("Count the stock every: enter a number of days from 1 to 365.");
        var badBearer = new ConsignmentDTOs.Terms(t.ownershipTransfer(), t.priceSetBy(), t.commissionPct(), t.settleEveryDays(),
                t.reconcileEveryDays(), t.countEveryDays(), t.shrinkageTolerancePct(), "NOBODY", t.expiryBearer(), t.insuredBy(),
                t.claimWindowDays(), t.proofRequired(), t.unsoldReturnDays(), t.unsoldReturnBearer());
        assertThatThrownBy(() -> service.offer(new ConsignmentDTOs.OfferRequest(SHOP, badBearer, null)))
                .hasMessage("Choose who bears damage.");
        assertThat(table).isEmpty();
    }

    @Test
    @DisplayName("[MKT-R2.2] only an approved seller other than the warehouse, and only once a warehouse is named")
    void whoCanBeAConsignor() {
        assertThatThrownBy(() -> service.offer(new ConsignmentDTOs.OfferRequest(WAREHOUSE, terms("10"), null)))
                .hasMessage("The warehouse cannot consign stock to itself.");
        assertThatThrownBy(() -> service.offer(new ConsignmentDTOs.OfferRequest(OTHER, terms("10"), null)))
                .hasMessage("Choose an approved seller account.");
        lenient().when(settings.warehouseOrg()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> offer("10")).hasMessage("Name the MaxTheService warehouse first: it holds consigned stock.");
    }

    @Test
    @DisplayName("[MKT-R2.1] [MKT-R4.6] the seller accepts: active; newer terms accepted later replace it, and the old terms are kept as they were")
    void acceptAndReplace() {
        ConsignmentDTOs.Agreement first = answer(offer("10"), "ACCEPT", null, false);
        assertThat(first.status()).isEqualTo("ACTIVE");
        assertThat(service.activeFor(SHOP, WAREHOUSE)).map(MarketplaceConsignmentAgreement::getAgreementNo).contains("CA-000001");
        ConsignmentDTOs.Agreement second = offer("8");
        assertThatThrownBy(() -> offer("7")).hasMessage("CA-000002 is still waiting for Shahzad Mobile Shop's answer. Withdraw it before offering new terms.");
        answer(second, "ACCEPT", null, false);
        assertThat(table.get(0).getStatus()).isEqualTo("REPLACED");
        assertThat(table.get(0).getReplacedById()).isEqualTo(2L);
        assertThat(table.get(0).getCommissionPct()).isEqualByComparingTo("10");
        assertThat(service.activeFor(SHOP, WAREHOUSE)).map(MarketplaceConsignmentAgreement::getAgreementNo).contains("CA-000002");
        verify(audit).event(eq("MKT_CONSIGNMENT_REPLACED"), eq("MKT_CONSIGNMENT_AGREEMENT"), eq("CA-000001"), eq(SHOP), any(),
                eq("ACTIVE"), eq("REPLACED"), any(), eq("Replaced by CA-000002"));
    }

    @Test
    @DisplayName("[MKT-R4.6] decline and end need a reason; the operator withdraws but never accepts; another business cannot answer")
    void whoAnswersWhat() {
        ConsignmentDTOs.Agreement a = offer("10");
        assertThatThrownBy(() -> answer(a, "DECLINE", " ", false)).hasMessage("Say why you decline, so MaxTheService can offer better terms.");
        assertThatThrownBy(() -> answer(a, "ACCEPT", null, true)).hasMessage("The seller accepts its own consignment terms.");
        lenient().when(access.org()).thenReturn(OTHER);
        assertThatThrownBy(() -> answer(a, "ACCEPT", null, false)).hasMessage("No such consignment agreement.");
        lenient().when(access.org()).thenReturn(SHOP);
        assertThat(answer(a, "WITHDRAW", null, true).status()).isEqualTo("WITHDRAWN");
        ConsignmentDTOs.Agreement b = answer(offer("10"), "ACCEPT", null, false);
        assertThatThrownBy(() -> answer(b, "END", null, true)).hasMessage("Say why the agreement ends.");
        ConsignmentDTOs.Agreement ended = answer(b, "END", "stopping consignment", false);
        assertThat(ended.status()).isEqualTo("ENDED");
        assertThat(ended.answerReason()).isEqualTo("stopping consignment");
        assertThat(service.activeFor(SHOP, WAREHOUSE)).isEmpty();
        assertThatThrownBy(() -> answer(ended, "ACCEPT", null, false)).hasMessage("Only an offer not yet answered can be accepted.");
    }

    @Test
    @DisplayName("[MKT-R4.6] a stale screen is refused; a non-operator cannot offer, and a user-tier member cannot bind the business")
    void guards() {
        ConsignmentDTOs.Agreement a = offer("10");
        assertThatThrownBy(() -> service.decide(new ConsignmentDTOs.DecisionRequest(a.id(), "ACCEPT", 99, null), false))
                .hasMessage("This agreement changed meanwhile. Refresh and try again.");
        doThrow(new ValidationException("Only the owner or an admin can accept the marketplace agreements for this business."))
                .when(access).assertOwnerOrAdmin();
        assertThatThrownBy(() -> answer(a, "ACCEPT", null, false)).isInstanceOf(ValidationException.class);
        assertThat(table.get(0).getStatus()).isEqualTo("OFFERED");
        doThrow(new AccessDeniedException("Access denied")).when(access).assertOperator();
        assertThatThrownBy(() -> offer("10")).isInstanceOf(AccessDeniedException.class);
        verify(agreements, never()).findAllByOrderByIdDesc(any());
    }
}
