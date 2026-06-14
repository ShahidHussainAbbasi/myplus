package com.myplus.inventory.controller;

import com.myplus.inventory.dto.ApiResponse;
import com.myplus.inventory.dto.CategoryDTO;
import com.myplus.inventory.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory/categories")
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
