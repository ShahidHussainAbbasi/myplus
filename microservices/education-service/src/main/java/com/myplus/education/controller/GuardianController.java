package com.myplus.education.controller;

import com.myplus.education.dto.ApiResponse;
import com.myplus.education.dto.EducationDTOs.GuardianDTO;
import com.myplus.education.security.AuthenticatedUser;
import com.myplus.education.service.GuardianService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/education/guardians")
@RequiredArgsConstructor
public class GuardianController {

    private final GuardianService guardianService;

    @GetMapping
    public ApiResponse<?> getAll(@AuthenticationPrincipal AuthenticatedUser user,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(guardianService.getByUser(user.getUserId(), PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable Long id) {
        return ApiResponse.success(guardianService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@RequestBody GuardianDTO dto,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        dto.setUserId(user.getUserId());
        return ApiResponse.success(guardianService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody GuardianDTO dto) {
        return ApiResponse.success(guardianService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        guardianService.delete(id);
    }
}
