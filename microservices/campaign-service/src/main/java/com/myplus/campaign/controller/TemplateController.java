package com.myplus.campaign.controller;

import com.myplus.campaign.dto.ApiResponse;
import com.myplus.campaign.dto.PageResponse;
import com.myplus.campaign.dto.TemplateDTO;
import com.myplus.campaign.service.TemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.function.Function;

@RestController
@RequestMapping("/api/campaign/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<TemplateDTO>>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(ApiResponse.success(
                PageResponse.of(templateService.getAll(pageable), Function.identity())));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TemplateDTO>> create(@Valid @RequestBody TemplateDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(templateService.createTemplate(dto)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TemplateDTO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(templateService.getById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TemplateDTO>> update(@PathVariable Long id, @Valid @RequestBody TemplateDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(templateService.updateTemplate(id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Template deleted"));
    }

    @GetMapping("/by-type/{type}")
    public ResponseEntity<ApiResponse<List<TemplateDTO>>> byType(@PathVariable String type) {
        return ResponseEntity.ok(ApiResponse.success(templateService.getByType(type)));
    }
}
