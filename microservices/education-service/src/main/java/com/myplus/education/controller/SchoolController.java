package com.myplus.education.controller;

import com.myplus.education.dto.ApiResponse;
import com.myplus.education.dto.EducationDTOs.SchoolDTO;
import com.myplus.education.security.AuthenticatedUser;
import com.myplus.education.service.SchoolService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/education/schools")
@RequiredArgsConstructor
public class SchoolController {

    private final SchoolService schoolService;

    @GetMapping
    public ApiResponse<?> getAll(@AuthenticationPrincipal AuthenticatedUser user,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(schoolService.getByUser(user.getUserId(), PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable Long id) {
        return ApiResponse.success(schoolService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@RequestBody SchoolDTO dto,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        dto.setUserId(user.getUserId());
        return ApiResponse.success(schoolService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody SchoolDTO dto) {
        return ApiResponse.success(schoolService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        schoolService.delete(id);
    }
}
