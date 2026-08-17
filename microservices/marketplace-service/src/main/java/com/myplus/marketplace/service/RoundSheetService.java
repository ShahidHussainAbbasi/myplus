package com.myplus.marketplace.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.myplus.commerce.contracts.client.TradeClient;
import com.myplus.commerce.contracts.dto.RoundFigureView;
import com.myplus.marketplace.dto.OrderDTO;
import com.myplus.marketplace.dto.OrderQuery;
import com.myplus.marketplace.dto.RoundSheetDTO;
import com.myplus.marketplace.support.AsOrg;

import lombok.RequiredArgsConstructor;

/**
 * OMS O8 slice 1 — assembles a delivery round's recovery sheet.
 *
 * <h3>The division of labour, and why it is drawn here</h3>
 * This service answers one question — <b>which invoices went out on the round</b> — because that is what
 * marketplace knows. It answers <b>none</b> of the money questions, because those are receivables and the books
 * hold them. A route sheet is a collection document: the salesman asks each shop for the amount printed on it,
 * so a figure this service computed itself would be a second opinion about the same debt, handed to a
 * shopkeeper, and the salesman would be collecting against a number the ledger does not recognise.
 *
 * <h3>One remote call for the whole round</h3>
 * The balances arrive in a single {@link TradeClient#roundFigures} batch. A round is 20–30 stops and this sheet
 * is printed at the end of every working day; a lookup per stop would be thirty round trips to produce one
 * page. That is the same reason the booking screen fetches stock levels once rather than per product pick.
 *
 * <h3>What happens when the books cannot be reached</h3>
 * The sheet is refused, deliberately — see {@link #forRound}. Every other read in this file degrades gracefully;
 * this one must not.
 */
@Service
@RequiredArgsConstructor
public class RoundSheetService {

    private static final Logger LOG = LoggerFactory.getLogger(RoundSheetService.class);

    /** A round is a day's dispatches. Comfortably above a real round, and a guard against an unbounded read. */
    private static final int MAX_STOPS = 500;

    private final OrderService orderService;
    private final TradeClient tradeClient;

    /**
     * The round for a date window: every FIELD order dispatched in it, with the money from the books.
     *
     * <p><b>SHIPPED and PARTIALLY_SHIPPED both count.</b> A part-dispatched order has goods on the van and an
     * invoice for what went out, so the shop owes for it and the salesman must have it on his sheet. Filtering
     * to SHIPPED alone would silently drop exactly those stops — the shop would be visited with no line to
     * record against, and the collection would go unrecorded.
     *
     * <p><b>DELIVERED is excluded</b>, because a delivered stop has already been keyed: it belongs to the
     * cash-up that follows, not to the sheet going out.
     *
     * @param from     first day of the round, inclusive
     * @param to       last day, inclusive; defaults to {@code from}
     * @param bookedBy optionally narrow to one rep's orders; null is everyone's
     * @throws IllegalStateException when the books cannot supply the balances — a sheet with blank money on it
     *                               is worse than no sheet, because a salesman will still take it out
     */
    @Transactional(readOnly = true)
    public RoundSheetDTO forRound(LocalDate from, LocalDate to, Long bookedBy, String salesman,
                                  Long orgId, Long userId) {
        LocalDate start = from != null ? from : LocalDate.now();
        LocalDate end = to != null ? to : start;

        List<OrderDTO> dispatched = new ArrayList<>();
        for (String status : new String[] { "SHIPPED", "PARTIALLY_SHIPPED" }) {
            dispatched.addAll(orderService.page(
                    OrderQuery.of(0, MAX_STOPS, status, null, "FIELD", start, end, null, false, bookedBy),
                    orgId, userId).getContent());
        }
        // One order can only be in one state, so the two pages cannot overlap. Sorted by order number so the
        // Sr column is stable between two prints of the same round — a salesman comparing his marked-up copy
        // against a reprint must find the rows in the same places.
        dispatched.sort((a, b) -> String.valueOf(a.getOrderNo()).compareTo(String.valueOf(b.getOrderNo())));

        List<String> invoiceNos = dispatched.stream()
                .map(OrderDTO::getInvoiceNo)
                .filter(no -> no != null && !no.isBlank())
                .distinct()
                .toList();

        Map<String, RoundFigureView> figures = fetchFigures(orgId, invoiceNos);

        List<RoundSheetDTO.Stop> stops = new ArrayList<>();
        BigDecimal invoiceTotal = BigDecimal.ZERO, totalDue = BigDecimal.ZERO;
        int sr = 0;

        for (OrderDTO o : dispatched) {
            RoundFigureView f = o.getInvoiceNo() == null ? null : figures.get(o.getInvoiceNo());
            // An order dispatched with no invoice cannot be collected against, so it has no place on a
            // recovery sheet. It is a real state (a backordered order dispatches nothing) and is skipped
            // rather than printed with zeros, which a salesman would read as "nothing to collect".
            if (f == null) {
                LOG.debug("Round sheet: skipping order {} — no invoice figures", o.getOrderNo());
                continue;
            }

            BigDecimal due = nz(f.getCustomerOutstanding());
            BigDecimal invoiced = nz(f.getInvoiceTotal());
            // Previous balance is DERIVED, never stored: total owed less what is still unpaid on THIS invoice.
            // Storing it as a third figure is how a sheet ends up disagreeing with itself.
            BigDecimal previous = due.subtract(nz(f.getInvoiceOutstanding()));
            if (previous.signum() < 0) previous = BigDecimal.ZERO;

            stops.add(RoundSheetDTO.Stop.builder()
                    .sr(++sr)
                    .orderId(o.getId())
                    .orderNo(o.getOrderNo())
                    .invoiceNo(f.getInvoiceNo())
                    .date(o.getCreatedAt() == null ? null : o.getCreatedAt().toLocalDate())
                    .customerId(f.getCustomerId())
                    // The BOOKS' account name, not the order's customerName: a branch of a trade group owes
                    // against the group, and the sheet must name whoever the debt actually sits on.
                    .accountName(f.getAccountName() != null ? f.getAccountName() : o.getCustomerName())
                    .area(o.getShippingAddress())
                    .invoiceTotal(invoiced)
                    .previousBalance(previous)
                    .totalDue(due)
                    .build());

            invoiceTotal = invoiceTotal.add(invoiced);
            totalDue = totalDue.add(due);
        }

        return RoundSheetDTO.builder()
                .from(start).to(end).salesman(salesman)
                .stops(stops)
                .stopCount(stops.size())
                .invoiceTotal(invoiceTotal)
                .totalDue(totalDue)
                .build();
    }

    /**
     * The round's money, in one call, keyed by invoice number.
     *
     * <p><b>Fails LOUD</b>, unlike the tax policy read on the checkout path. There the fail-closed answer is
     * visibly wrong to a shopkeeper and recoverable; here it is not. A sheet printed with blank or zero
     * balances still looks like a sheet, a salesman will take it out, and he will spend the day asking shops
     * for the wrong money — or for none. Refusing to print is the only safe failure.
     */
    private Map<String, RoundFigureView> fetchFigures(Long orgId, List<String> invoiceNos) {
        Map<String, RoundFigureView> byInvoice = new HashMap<>();
        if (invoiceNos.isEmpty()) return byInvoice;
        try {
            List<RoundFigureView> rows = AsOrg.call(orgId, () -> tradeClient.roundFigures(invoiceNos));
            if (rows != null) for (RoundFigureView r : rows) {
                if (r != null && r.getInvoiceNo() != null) byInvoice.put(r.getInvoiceNo(), r);
            }
        } catch (RuntimeException ex) {
            LOG.error("Round sheet for org {} could not read the balances for {} invoice(s)",
                    orgId, invoiceNos.size(), ex);
            throw new IllegalStateException(
                    "The balances could not be read, so the round sheet has not been produced. "
                            + "A sheet with missing figures would send the salesman out asking for the wrong "
                            + "amounts. Try again in a moment.", ex);
        }
        return byInvoice;
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
}
