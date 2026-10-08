package com.myplus.catalog.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.catalog.dto.CategoryDTO;
import com.myplus.catalog.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/catalog/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryDTO>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(categoryService.getAll()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CategoryDTO>> create(@RequestBody CategoryDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(categoryService.create(dto), "Created"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryDTO>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(categoryService.getById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryDTO>> update(@PathVariable Long id, @RequestBody CategoryDTO dto) {
        return ResponseEntity.ok(ApiResponse.success(categoryService.update(id, dto), "Updated"));
    }

    /** PR-2b — a category's markup % (body {"markupPct": 14.5}, or null to clear). Owner/admin: it moves prices. */
    @PutMapping("/{id}/markup")
    @PreAuthorize("hasAuthority('ROLE_OWNER') or hasAuthority('ADMIN_PRIVILEGE') or hasAuthority('SUPER_PRIVILEGE')")
    public ResponseEntity<ApiResponse<CategoryDTO>> setMarkup(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body) {
        Object v = body == null ? null : body.get("markupPct");
        java.math.BigDecimal pct;
        try {
            pct = (v == null || String.valueOf(v).isBlank()) ? null : new java.math.BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new com.myplus.common.web.exception.ValidationException("Markup must be a number");
        }
        return ResponseEntity.ok(ApiResponse.success(categoryService.setMarkup(id, pct), "Markup saved"));
    }

    @PreAuthorize("hasAuthority('DELETE_PRIVILEGE')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Deleted"));
    }

    @GetMapping("/tree")
    public ResponseEntity<ApiResponse<List<CategoryDTO>>> tree() {
        return ResponseEntity.ok(ApiResponse.success(categoryService.getTree()));
    }
}
