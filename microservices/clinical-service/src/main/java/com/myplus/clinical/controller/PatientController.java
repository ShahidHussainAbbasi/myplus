package com.myplus.clinical.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.myplus.clinical.dto.PatientDtos.PatientView;
import com.myplus.clinical.dto.PatientDtos.PhoneLookup;
import com.myplus.clinical.dto.PatientDtos.RegisterRequest;
import com.myplus.clinical.dto.PatientDtos.UpdateRequest;
import com.myplus.clinical.service.PatientExistsException;
import com.myplus.clinical.service.PatientService;
import com.myplus.common.web.ApiResponse;

import lombok.RequiredArgsConstructor;

/**
 * HMS S1 — the front desk's patient register, at {@code /api/clinic/patients}. The organisation is always the
 * caller's (from the token); an id from another clinic answers "Patient not found", exactly like a missing one.
 */
@RestController
@RequestMapping("/api/clinic/patients")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patients;

    /** {@code ?phone=} — who is on this number; else {@code ?q=} — search; else the most recent. */
    @GetMapping
    public ApiResponse<?> find(@RequestParam(required = false) String phone, @RequestParam(required = false) String q) {
        if (phone != null) {
            PhoneLookup found = patients.lookup(phone);
            return ApiResponse.success(found);
        }
        List<PatientView> rows = patients.search(q);
        return ApiResponse.success(rows);
    }

    @GetMapping("/{id}")
    public ApiResponse<PatientView> get(@PathVariable Long id) {
        return ApiResponse.success(patients.get(id));
    }

    @PostMapping
    public ApiResponse<PatientView> register(@RequestBody RegisterRequest req) {
        PatientView v = patients.register(req);
        return ApiResponse.success(v, v.isLinkPending()
                ? "Patient registered — " + v.getMrn() + ". The pharmacy customer link is pending; it will be retried."
                : "Patient registered — " + v.getMrn() + ".");
    }

    @PutMapping("/{id}")
    public ApiResponse<PatientView> update(@PathVariable Long id, @RequestBody UpdateRequest req) {
        return ApiResponse.success(patients.update(id, req), "Patient updated.");
    }

    @PostMapping("/{id}/link")
    public ApiResponse<PatientView> link(@PathVariable Long id) {
        PatientView v = patients.link(id);
        return ApiResponse.success(v, v.isLinkPending() ? "The customer link is still pending." : "Linked to the pharmacy customer.");
    }

    @PreAuthorize("hasAnyAuthority('ROLE_OWNER','ADMIN_PRIVILEGE')")
    @PostMapping("/{id}/retire")
    public ApiResponse<PatientView> retire(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.success(patients.retire(id, body == null ? null : body.get("reason")), "Patient retired.");
    }

    /**
     * M-02 — the phone is already registered: the platform's refusal envelope (200, success:false) carrying the
     * patient, so the screen can open them instead of showing an error.
     */
    @ExceptionHandler(PatientExistsException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> exists(PatientExistsException e) {
        return ResponseEntity.ok(new ApiResponse<>(false, e.getMessage(), Map.of("existing", e.getExisting()), 409));
    }
}
