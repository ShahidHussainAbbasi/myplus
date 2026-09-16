package com.myplus.catalog.service;

import com.myplus.catalog.dto.CategoryDTO;
import com.myplus.catalog.entity.Category;
import com.myplus.common.security.CurrentUser;
import com.myplus.common.web.exception.ResourceNotFoundException;
import com.myplus.catalog.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    // CACHE-2 — the category list is cached per tenant; every writer here publishes CatalogCategoriesChanged so it is
    // evicted AFTER the commit (CatalogRefsCache.onCategoriesChanged), never inside the transaction.
    private final CatalogRefsCache refsCache;
    private final ApplicationEventPublisher events;

    public List<CategoryDTO> getAll() {
        Long org = CurrentUser.organizationId();
        Long user = CurrentUser.userId();
        // CACHE-2 — cache-aside: this tenant + user from the cache; on a miss the database, kept with a TTL.
        return refsCache.categories(org, user,
                () -> categoryRepository.findScoped(org, user).stream().map(this::toDto).toList());
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
        Category saved = categoryRepository.save(c);
        changed(saved);
        return toDto(saved);
    }

    @Transactional
    public CategoryDTO update(Long id, CategoryDTO dto) {
        Category c = getEntity(id);
        c.setName(dto.getName());
        c.setDescription(dto.getDescription());
        c.setParentCategory(dto.getParentId() != null ? getEntity(dto.getParentId()) : null);
        Category saved = categoryRepository.save(c);
        changed(saved);
        return toDto(saved);
    }

    @Transactional
    public void delete(Long id) {
        Category c = getEntity(id);
        categoryRepository.delete(c);
        // A category still on a product fails the FK at commit; the transaction rolls back and nothing is evicted.
        changed(c);
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

    /** CACHE-2 — published inside the writer's transaction; both orgs: the caller's and the category's own. */
    private void changed(Category c) {
        events.publishEvent(CatalogCategoriesChanged.of(CurrentUser.organizationId(), c.getOrganizationId()));
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
