package com.myplus.auth.service;

import com.myplus.auth.entity.User;
import com.myplus.auth.exception.ResourceNotFoundException;
import com.myplus.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.jboss.aerogear.security.otp.Totp;
import org.jboss.aerogear.security.otp.api.Base32;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class TwoFactorService {

    private final UserRepository userRepository;

    public String generateSecret() {
        return Base32.random();
    }

    public String getQRUrl(String secret, String email) {
        String issuer = URLEncoder.encode("MyPlus", StandardCharsets.UTF_8);
        String label = URLEncoder.encode(email, StandardCharsets.UTF_8);
        return "otpauth://totp/" + issuer + ":" + label + "?secret=" + secret + "&issuer=" + issuer;
    }

    public boolean verifyCode(String secret, String code) {
        if (secret == null || code == null) return false;
        Totp totp = new Totp(secret);
        try {
            return totp.verify(code);
        } catch (Exception ex) {
            return false;
        }
    }

    @Transactional
    public String enableTwoFactor(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        String secret = generateSecret();
        user.setTwoFactorSecret(secret);
        user.setTwoFactorEnabled(true);
        userRepository.save(user);
        return getQRUrl(secret, user.getEmail());
    }

    @Transactional
    public void disableTwoFactor(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        userRepository.save(user);
    }
}
