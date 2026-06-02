package com.myplus.campaign.controller;

import com.myplus.campaign.dto.ApiResponse;
import com.myplus.campaign.dto.CampaignDTO;
import com.myplus.campaign.dto.CampaignStatsDTO;
import com.myplus.campaign.dto.PageResponse;
import com.myplus.campaign.security.AuthenticatedUser;
import com.myplus.campaign.service.CampaignService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;

@RestController
@RequestMapping("/api/campaign/campaigns")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService campaignService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CampaignDTO>>> getAllCampaigns(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        Pageable pageable = PageRequest.of(page, size, parseSort(sort));
        Long userId = user != null ? user.getUserId() : null;
        Page<CampaignDTO> result = campaignService.getAllCampaigns(userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(result, Function.identity())));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CampaignDTO>> createCampaign(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CampaignDTO dto) {
        Long userId = user != null ? user.getUserId() : null;
        return ResponseEntity.ok(ApiResponse.success(campaignService.createCampaign(dto, userId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CampaignDTO>> getCampaign(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(campaignService.getCampaignById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CampaignDTO>> updateCampaign(
            @PathVariable Long id, @Valid @RequestBody CampaignDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(campaignService.updateCampaign(id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCampaign(@PathVariable Long id) {
        campaignService.deleteCampaign(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Campaign deleted"));
    }

    @PostMapping("/{id}/launch")
    public ResponseEntity<ApiResponse<CampaignDTO>> launch(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(campaignService.launchCampaign(id)));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<ApiResponse<CampaignDTO>> pause(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(campaignService.pauseCampaign(id)));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<CampaignDTO>> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(campaignService.cancelCampaign(id)));
    }

    @PostMapping("/{id}/schedule")
    public ResponseEntity<ApiResponse<CampaignDTO>> schedule(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        LocalDateTime when = LocalDateTime.parse(body.get("scheduledAt"));
        return ResponseEntity.ok(ApiResponse.success(campaignService.scheduleCampaign(id, when)));
    }

    @GetMapping("/{id}/stats")
    public ResponseEntity<ApiResponse<CampaignStatsDTO>> stats(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(campaignService.getCampaignStats(id)));
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<ApiResponse<CampaignDTO>> clone(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(campaignService.cloneCampaign(id)));
    }

    private Sort parseSort(String sort) {
        String[] parts = sort.split(",");
        Sort.Direction dir = parts.length > 1 && "desc".equalsIgnoreCase(parts[1])
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(dir, parts[0]);
    }
}
