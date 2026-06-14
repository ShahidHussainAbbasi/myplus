package com.myplus.campaign.controller;

import com.myplus.campaign.dto.ApiResponse;
import com.myplus.campaign.dto.AudienceDTO;
import com.myplus.campaign.dto.AudienceMemberDTO;
import com.myplus.campaign.dto.PageResponse;
import com.myplus.campaign.service.AudienceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.function.Function;

@RestController
@RequestMapping("/api/campaign/audiences")
@RequiredArgsConstructor
public class AudienceController {

    private final AudienceService audienceService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AudienceDTO>>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(audienceService.getAll(pageable), Function.identity())));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AudienceDTO>> create(@Valid @RequestBody AudienceDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(audienceService.createAudience(dto)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AudienceDTO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(audienceService.getById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AudienceDTO>> update(@PathVariable Long id, @Valid @RequestBody AudienceDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(audienceService.updateAudience(id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        audienceService.deleteAudience(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Audience deleted"));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<ApiResponse<AudienceMemberDTO>> addMember(
            @PathVariable Long id, @Valid @RequestBody AudienceMemberDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(audienceService.addMember(id, dto)));
    }

    @DeleteMapping("/{id}/members/{memberId}")
    public ResponseEntity<ApiResponse<Void>> removeMember(
            @PathVariable Long id, @PathVariable Long memberId) {
        audienceService.removeMember(id, memberId);
        return ResponseEntity.ok(ApiResponse.success(null, "Member removed"));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<ApiResponse<PageResponse<AudienceMemberDTO>>> getMembers(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(audienceService.getMembers(id, pageable), Function.identity())));
    }

    @PostMapping("/{id}/members/import")
    public ResponseEntity<ApiResponse<List<AudienceMemberDTO>>> importMembers(
            @PathVariable Long id, @RequestBody List<AudienceMemberDTO> members) {
        return ResponseEntity.ok(ApiResponse.success(audienceService.importMembers(id, members)));
    }
}
