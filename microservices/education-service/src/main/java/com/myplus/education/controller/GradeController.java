package com.myplus.education.controller;

import com.myplus.education.dto.ApiResponse;
import com.myplus.education.dto.EducationDTOs.GradeDTO;
import com.myplus.education.security.AuthenticatedUser;
import com.myplus.education.service.GradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/education/grades")
@RequiredArgsConstructor
public class GradeController {

    private final GradeService gradeService;

    @GetMapping
    public ApiResponse<?> getAll(@AuthenticationPrincipal AuthenticatedUser user,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(gradeService.getByUser(user.getUserId(), PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable Long id) {
        return ApiResponse.success(gradeService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@RequestBody GradeDTO dto,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        dto.setUserId(user.getUserId());
        return ApiResponse.success(gradeService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody GradeDTO dto) {
        return ApiResponse.success(gradeService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        gradeService.delete(id);
    }
}
