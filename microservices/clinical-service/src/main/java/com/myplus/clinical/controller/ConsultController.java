package com.myplus.clinical.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.clinical.dto.ConsultDtos.EncounterUpdate;
import com.myplus.clinical.dto.ConsultDtos.EncounterView;
import com.myplus.clinical.dto.ConsultDtos.NoteRequest;
import com.myplus.clinical.dto.ConsultDtos.RxRequest;
import com.myplus.clinical.dto.ConsultDtos.TemplateRequest;
import com.myplus.clinical.dto.ConsultDtos.TemplateView;
import com.myplus.clinical.service.RxTemplateService;
import com.myplus.clinical.dto.QueueDtos.TokenView;
import com.myplus.clinical.service.ConsultService;
import com.myplus.clinical.service.QueueService;
import com.myplus.common.web.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * HMS S3a — the doctor's workspace, at {@code /api/clinic/consult}. Every call needs {@code clinic.consult}
 * (checked in the services, so a direct API call is refused exactly like the screen); another clinic's visit or
 * token answers "not found".
 */
@RestController
@RequestMapping("/api/clinic/consult")
@RequiredArgsConstructor
public class ConsultController {

    private final ConsultService consult;
    private final QueueService queue;
    private final RxTemplateService templates;

    /** Call the next waiting patient of this doctor (atomic: two doctors never get the same one). */
    @PostMapping("/next")
    public ApiResponse<TokenView> next(@RequestParam Long providerId) {
        return queue.callNext(providerId)
                .map(t -> ApiResponse.success(t, "Calling " + t.getTokenLabel() + " — " + t.getPatientName() + "."))
                .orElseGet(() -> new ApiResponse<>(false, "Nobody is waiting for this doctor.", null, 200));
    }

    @PostMapping("/tokens/{tokenId}/start")
    public ApiResponse<EncounterView> start(@PathVariable Long tokenId) {
        return ApiResponse.success(consult.start(tokenId));
    }

    /** The visit of a token, or data=null when it has not started. */
    @GetMapping("/tokens/{tokenId}")
    public ApiResponse<EncounterView> byToken(@PathVariable Long tokenId) {
        return ApiResponse.success(consult.byToken(tokenId));
    }

    @GetMapping("/encounters/{id}")
    public ApiResponse<EncounterView> open(@PathVariable Long id) {
        return ApiResponse.success(consult.open(id));
    }

    @PutMapping("/encounters/{id}")
    public ApiResponse<EncounterView> update(@PathVariable Long id, @RequestBody EncounterUpdate req) {
        return ApiResponse.success(consult.update(id, req), "Saved.");
    }

    @PostMapping("/encounters/{id}/notes")
    public ApiResponse<EncounterView> note(@PathVariable Long id, @RequestBody NoteRequest req) {
        return ApiResponse.success(consult.note(id, req), "Note added.");
    }

    @PostMapping("/encounters/{id}/complete")
    public ApiResponse<EncounterView> complete(@PathVariable Long id) {
        return ApiResponse.success(consult.complete(id), "Visit completed.");
    }

    // ── S3b-2: the clinic's prescription templates ─────────────────────────────────────────────────

    @GetMapping("/templates")
    public ApiResponse<List<TemplateView>> templates() {
        return ApiResponse.success(templates.list());
    }

    @PostMapping("/templates")
    public ApiResponse<TemplateView> saveTemplate(@RequestBody TemplateRequest req) {
        TemplateView v = templates.create(req);
        return ApiResponse.success(v, "Saved as the template '" + v.getName() + "'.");
    }

    @PostMapping("/templates/{id}/retire")
    public ApiResponse<Void> retireTemplate(@PathVariable Long id) {
        templates.retire(id);
        return ApiResponse.success(null, "Template removed.");
    }

    /** S3b-1 — the doctor's prescription, saved as a whole; refused once it is with the pharmacy. */
    @PutMapping("/encounters/{id}/rx")
    public ApiResponse<EncounterView> saveRx(@PathVariable Long id, @RequestBody RxRequest req) {
        return ApiResponse.success(consult.saveRx(id, req), "Prescription saved.");
    }

    /** S3b-1 — send it to the pharmacy. A second Submit returns the same prescription, never a second one. */
    @PostMapping("/encounters/{id}/rx/submit")
    public ApiResponse<EncounterView> submitRx(@PathVariable Long id) {
        EncounterView v = consult.submitRx(id);
        return ApiResponse.success(v, "Sent to the pharmacy" + (v.getTokenLabel() == null ? "." : " with token " + v.getTokenLabel() + "."));
    }
}
