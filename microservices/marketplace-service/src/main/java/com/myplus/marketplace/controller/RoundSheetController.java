package com.myplus.marketplace.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.ApiResponse;
import com.myplus.marketplace.dto.RoundKeyDTO;
import com.myplus.marketplace.dto.RoundSheetDTO;
import com.myplus.marketplace.service.RoundKeyingService;
import com.myplus.marketplace.service.RoundSheetService;

import lombok.RequiredArgsConstructor;

/**
 * OMS O8 — the delivery round's recovery sheet.
 *
 * <h3>Its own controller, not a branch of OrderController</h3>
 * A round spans orders. One sheet covers every stop of a day's dispatches, so it is not addressable under
 * {@code /orders/{id}/…} without pretending it belongs to one of them — the same reason driver settlement has
 * its own controller.
 *
 * <h3>Admin-gated, reads included</h3>
 * This document states which shops owe how much, account by account, and is the basis on which cash is
 * collected. That is the same class of information as the driver-settlement screen, which is gated for exactly
 * this reason: an open-balances list is not ordinary operational data. The salesman carries a printed copy the
 * office produced; he does not query the endpoint.
 */
@RestController
@RequestMapping("/round-sheet")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE','SUPER_PRIVILEGE')")
public class RoundSheetController {

    private final RoundSheetService roundSheetService;
    private final RoundKeyingService roundKeyingService;

    /**
     * The round for a date window, with the money from the books.
     *
     * <p>Both dates are optional and default to today — the overwhelmingly common case is "print today's
     * round", and requiring the operator to type a date they mean anyway is friction for nothing.
     *
     * @param bookedBy narrow to one rep's orders; omit for every dispatch in the window
     * @param salesman a name for the heading. Free text on purpose: the person carrying the sheet is often not
     *                 a system user at all — a driver, a hired van — and refusing to name them would leave the
     *                 one field the cashier needs blank.
     */
    @GetMapping
    public ApiResponse<RoundSheetDTO> sheet(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long bookedBy,
            @RequestParam(required = false) String salesman) {
        return ApiResponse.success(
                roundSheetService.forRound(from, to, bookedBy, salesman,
                        CurrentUser.organizationId(), CurrentUser.userId()),
                "Round sheet");
    }

    /**
     * OMS O8 slice 5 — key the whole round back in from the marked-up sheet.
     *
     * <h3>One request per round, not per stop</h3>
     * That is how the paper works: the salesman hands back one sheet with an amount written against each line.
     * Twenty-nine separate saves is twenty-nine chances to be interrupted half way through and no way to tell
     * afterwards how far you got.
     *
     * <h3>Partial success is REPORTED, not thrown</h3>
     * A stop that cannot be keyed comes back in {@code skipped} with a reason while the rest proceed. Refusing
     * the batch over one shop would leave the operator with no way to key the other twenty-eight, and no
     * indication of which one was the problem. The commonest reason is "already keyed", which is what makes
     * pressing this twice harmless.
     *
     * <p>Same gate as the sheet itself: this records deliveries and posts receipts against real accounts.
     */
    @PostMapping("/key")
    public ApiResponse<RoundKeyDTO.Result> key(@RequestBody RoundKeyDTO request) {
        return ApiResponse.success(
                roundKeyingService.keyRound(request, CurrentUser.organizationId(),
                        CurrentUser.userId(), CurrentUser.email()),
                "Round keyed");
    }
}
