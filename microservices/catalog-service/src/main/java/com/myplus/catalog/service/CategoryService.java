package com.myplus.catalog.service;

import com.myplus.catalog.dto.CategoryDTO;
import com.myplus.catalog.entity.Category;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.catalog.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public List<CategoryDTO> getAll() {
        return categoryRepository.findScoped(CurrentUser.organizationId(), CurrentUser.userId())
                .stream().map(this::toDto).toList();
    }

    public CategoryDTO getById(Long id) {
        return toDto(getEntity(id));
    }

    @Transactional
    public CategoryDTO create(CategoryDTO dto) {
        Category c = Category.builder()
                .name(dto.getName())
                .description(dto.getDescription())
                .parentCategory(dto.getParentId() != null ? getEntity(dto.getParentId()) : null)
                .organizationId(CurrentUser.organizationId())
                .userId(CurrentUser.userId())
                .build();
        return toDto(categoryRepository.save(c));
    }

    @Transactional
    public CategoryDTO update(Long id, CategoryDTO dto) {
        Category c = getEntity(id);
        c.setName(dto.getName());
        c.setDescription(dto.getDescription());
        c.setParentCategory(dto.getParentId() != null ? getEntity(dto.getParentId()) : null);
        return toDto(categoryRepository.save(c));
    }

    @Transactional
    public void delete(Long id) {
        categoryRepository.delete(getEntity(id));
    }

    public List<CategoryDTO> getTree() {
        return categoryRepository.findRootsScoped(CurrentUser.organizationId(), CurrentUser.userId())
                .stream().map(this::toTreeDto).toList();
    }

    private CategoryDTO toTreeDto(Category c) {
        CategoryDTO dto = toDto(c);
        dto.setChildren(categoryRepository.findByParentScoped(c.getId(), CurrentUser.organizationId(), CurrentUser.userId())
                .stream().map(this::toTreeDto).toList());
        return dto;
    }

    /** Scoped lookup — anti-IDOR. */
    public Category getEntity(Long id) {
        return categoryRepository.findByIdScoped(id, CurrentUser.organizationId(), CurrentUser.userId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));
    }

    private CategoryDTO toDto(Category c) {
        return CategoryDTO.builder()
                .id(c.getId())
                .name(c.getName())
                .description(c.getDescription())
                .parentId(c.getParentCategory() != null ? c.getParentCategory().getId() : null)
                .build();
    }
}
