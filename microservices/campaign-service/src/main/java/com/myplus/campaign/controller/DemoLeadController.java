package com.myplus.campaign.controller;

import com.myplus.campaign.dto.ApiResponse;
import com.myplus.campaign.dto.DemoRequestRequest;
import com.myplus.campaign.service.DemoLeadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public Book-a-Demo lead capture. Open (no auth): permitted by SecurityConfig ("/api/campaign/public/**")
 * and listed in the gateway's OPEN_API_ENDPOINTS. The monolith proxies anonymous form submissions here.
 */
@RestController
@RequestMapping("/api/campaign/public")
@RequiredArgsConstructor
public class DemoLeadController {

    private final DemoLeadService demoLeadService;

    @PostMapping("/demo-request")
    public ResponseEntity<ApiResponse<Void>> submit(@Valid @RequestBody DemoRequestRequest request) {
        demoLeadService.submit(request);
        return ResponseEntity.ok(ApiResponse.success(null, "Demo request received"));
    }
}
