package com.myplus.education.controller;

import com.myplus.education.dto.ApiResponse;
import com.myplus.education.dto.EducationDTOs.StudentDTO;
import com.myplus.education.security.AuthenticatedUser;
import com.myplus.education.service.StudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/education/students")
@RequiredArgsConstructor
public class StudentController {

    private final StudentService studentService;

    @GetMapping
    public ApiResponse<?> getAll(@AuthenticationPrincipal AuthenticatedUser user,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(studentService.getByUser(user.getUserId(), PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable Long id) {
        return ApiResponse.success(studentService.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> create(@RequestBody StudentDTO dto,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        dto.setUserId(user.getUserId());
        return ApiResponse.success(studentService.create(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody StudentDTO dto) {
        return ApiResponse.success(studentService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        studentService.delete(id);
    }
}
