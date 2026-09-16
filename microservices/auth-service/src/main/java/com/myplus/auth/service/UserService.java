package com.myplus.auth.service;

import com.myplus.auth.dto.ChangePasswordRequest;
import com.myplus.auth.dto.UpdateProfileRequest;
import com.myplus.auth.dto.UserProfileDTO;
import com.myplus.auth.entity.Role;
import com.myplus.auth.entity.User;
import com.myplus.auth.exception.ResourceNotFoundException;
import com.myplus.auth.exception.ValidationException;
import com.myplus.auth.repository.RoleRepository;
import com.myplus.auth.repository.UserRepository;
import com.myplus.auth.security.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    // AUTH-SESS-1 (defect B) — a credential change ends the sessions that were opened with the old one.
    private final RefreshTokenService refreshTokenService;

    public UserProfileDTO getCurrentUser(Long userId) {
        return toDto(getUser(userId));
    }

    public UserProfileDTO getUserById(Long id) {
        return toDto(getUser(id));
    }

    public Page<UserProfileDTO> getAllUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(this::toDto);
    }

    @Transactional
    public UserProfileDTO updateProfile(Long userId, UpdateProfileRequest request) {
        User user = getUser(userId);
        if (request.getFirstName() != null) user.setFirstName(request.getFirstName());
        if (request.getLastName() != null) user.setLastName(request.getLastName());
        if (request.getPhone() != null) user.setPhone(request.getPhone());
        return toDto(userRepository.save(user));
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = getUser(userId);
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new ValidationException("Current password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        // AUTH-SESS-1 — the point of changing a password is that the old one stops working. A session opened with it
        // would otherwise keep refreshing for up to 7 days. Note the ORDER: the wrong current password throws above,
        // so a failed attempt revokes nothing.
        refreshTokenService.deleteByUserId(userId);
    }

    @Transactional
    public void lockUser(Long id) {
        User user = getUser(id);
        user.setAccountNonLocked(false);
        userRepository.save(user);
        // AUTH-SESS-1 — a locked account must not keep trading on sessions it already has. accountNonLocked is read
        // at LOGIN, so without this a lock only stops the next sign-in, not the tills already signed in.
        refreshTokenService.deleteByUserId(id);
    }

    @Transactional
    public void unlockUser(Long id) {
        User user = getUser(id);
        user.setAccountNonLocked(true);
        user.setFailedLoginAttempts(0);
        user.setLockTime(null);
        userRepository.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        User user = getUser(id);
        userRepository.delete(user);
    }

    public Set<String> getUserRoles(Long id) {
        return CustomUserDetailsService.getRoleNames(getUser(id).getRoles());
    }

    @Transactional
    public UserProfileDTO updateUserRoles(Long id, List<String> roleNames) {
        User user = getUser(id);
        Set<Role> roles = new HashSet<>();
        for (String name : roleNames) {
            roles.add(roleRepository.findByName(name)
                    .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + name)));
        }
        user.setRoles(roles);
        return toDto(userRepository.save(user));
    }

    private User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    public UserProfileDTO toDto(User user) {
        return UserProfileDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone())
                .enabled(user.isEnabled())
                .accountNonLocked(user.isAccountNonLocked())
                .twoFactorEnabled(user.isTwoFactorEnabled())
                .userType(user.getUserType())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .roles(CustomUserDetailsService.getRoleNames(user.getRoles()))
                .build();
    }
}
