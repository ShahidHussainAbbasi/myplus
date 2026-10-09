package com.myplus.clinical.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.clinical.dto.QueueDtos.DayRequest;
import com.myplus.clinical.dto.QueueDtos.DoctorView;
import com.myplus.clinical.dto.QueueDtos.IssueRequest;
import com.myplus.clinical.dto.QueueDtos.MoveRequest;
import com.myplus.clinical.dto.QueueDtos.NewDoctorRequest;
import com.myplus.clinical.dto.QueueDtos.TokenView;
import com.myplus.clinical.service.QueueService;
import com.myplus.common.web.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * HMS S2 — the clinic's doctors and today's line, at {@code /api/clinic}. The organisation is always the caller's;
 * another clinic's token or doctor answers "not found", exactly like a missing one.
 *
 * <p>Every member of the clinic may use these in S2 (the front desk). S3 gives the doctor's moves (call, start,
 * park, resume, complete) their own role when the doctor's screen arrives.
 */
@RestController
@RequestMapping("/api/clinic")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queue;

    @GetMapping("/doctors")
    public ApiResponse<List<DoctorView>> doctors() {
        return ApiResponse.success(queue.doctors());
    }

    @PostMapping("/doctors")
    public ApiResponse<DoctorView> addDoctor(@RequestBody NewDoctorRequest req) {
        DoctorView d = queue.addDoctor(req);
        return ApiResponse.success(d, d.getName() + " added" + (d.getUsualLimit() == null ? ", no daily limit." : ", " + d.getUsualLimit() + " patients a day."));
    }

    @PostMapping("/doctors/day")
    public ApiResponse<DoctorView> day(@RequestBody DayRequest req) {
        return ApiResponse.success(queue.setDay(req), "Saved for the day.");
    }

    /** {@code ?providerId=} one doctor's line today; else the whole board. */
    @GetMapping("/queue")
    public ApiResponse<List<TokenView>> queue(@RequestParam(required = false) Long providerId) {
        return ApiResponse.success(providerId == null ? queue.board() : queue.line(providerId));
    }

    @PostMapping("/tokens")
    public ApiResponse<TokenView> issue(@RequestBody IssueRequest req) {
        TokenView t = queue.issue(req);
        return ApiResponse.success(t, "Token " + t.getTokenLabel() + " for " + t.getProviderName() + ".");
    }

    @GetMapping("/tokens/{id}")
    public ApiResponse<TokenView> get(@PathVariable Long id) {
        return ApiResponse.success(queue.get(id));
    }

    /** call · recall · start · park · resume · complete · cancel · noShow */
    @PostMapping("/tokens/{id}/{action}")
    public ApiResponse<TokenView> move(@PathVariable Long id, @PathVariable String action,
                                       @RequestBody(required = false) MoveRequest req) {
        return ApiResponse.success(queue.move(id, action, req == null ? null : req.getReason()));
    }

    @PostMapping("/queue/next")
    public ApiResponse<TokenView> next(@RequestParam Long providerId) {
        return queue.callNext(providerId)
                .map(t -> ApiResponse.success(t, "Calling " + t.getTokenLabel() + "."))
                .orElseGet(() -> new ApiResponse<>(false, "Nobody is waiting for this doctor.", null, 200));
    }
}
