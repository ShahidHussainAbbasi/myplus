package com.myplus.education.controller;

import com.myplus.education.dto.ApiResponse;
import com.myplus.education.dto.EducationDTOs.OwnerDTO;
import com.myplus.education.security.AuthenticatedUser;
import com.myplus.education.service.OwnerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/education/owners")
@RequiredArgsConstructor
public class OwnerController {

    private final OwnerService ownerService;

    @GetMapping
    public ApiResponse<?> getAll(@AuthenticationPrincipal AuthenticatedUser user,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(ownerService.getByUser(user.getUserId(), PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable Long id) {
        return ApiResponse.success(ownerService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@RequestBody OwnerDTO dto,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        dto.setUserId(user.getUserId());
        return ApiResponse.success(ownerService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody OwnerDTO dto) {
        return ApiResponse.success(ownerService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        ownerService.delete(id);
    }
}
