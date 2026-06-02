package com.myplus.auth.controller;

import com.myplus.auth.dto.*;
import com.myplus.auth.entity.User;
import com.myplus.auth.repository.UserRepository;
import com.myplus.auth.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/auth/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserRepository userRepository;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileDTO>> getMe(@AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(ApiResponse.success(userService.getCurrentUser(user.getId())));
    }

    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileDTO>> updateMe(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody UpdateProfileRequest request) {
        User user = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        return ResponseEntity.ok(ApiResponse.success(userService.updateProfile(user.getId(), request)));
    }

    @PutMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ChangePasswordRequest request) {
        User user = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        userService.changePassword(user.getId(), request);
        return ResponseEntity.ok(ApiResponse.success(null, "Password changed"));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<PageResponse<UserProfileDTO>>> getAllUsers(Pageable pageable) {
        Page<UserProfileDTO> page = userService.getAllUsers(pageable);
        return ResponseEntity.ok(ApiResponse.success(PageResponse.of(page, u -> u)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<UserProfileDTO>> getUser(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(userService.getUserById(id)));
    }

    @PutMapping("/{id}/lock")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> lock(@PathVariable Long id) {
        userService.lockUser(id);
        return ResponseEntity.ok(ApiResponse.success(null, "User locked"));
    }

    @PutMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> unlock(@PathVariable Long id) {
        userService.unlockUser(id);
        return ResponseEntity.ok(ApiResponse.success(null, "User unlocked"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.ok(ApiResponse.success(null, "User deleted"));
    }

    @GetMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<Set<String>>> getRoles(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(userService.getUserRoles(id)));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ApiResponse<UserProfileDTO>> updateRoles(
            @PathVariable Long id,
            @RequestBody Map<String, List<String>> body) {
        return ResponseEntity.ok(ApiResponse.success(userService.updateUserRoles(id, body.get("roles"))));
    }
}
