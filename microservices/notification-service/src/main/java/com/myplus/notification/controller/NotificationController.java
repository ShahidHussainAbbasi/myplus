package com.myplus.notification.controller;

import com.myplus.common.web.ApiResponse;
import com.myplus.notification.dto.EmailRequest;
import com.myplus.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Notification API (slice 33, Phase 8). Other services POST here instead of owning SMTP.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping("/email")
    public ResponseEntity<ApiResponse<Boolean>> email(@RequestBody EmailRequest request) {
        boolean sent = notificationService.sendEmail(request);
        return ResponseEntity.ok(ApiResponse.success(sent, sent ? "Email sent" : "Email send failed"));
    }
}
