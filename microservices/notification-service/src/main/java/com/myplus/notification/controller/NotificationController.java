package com.myplus.notification.controller;

import com.myplus.common.notify.EmailRequest;
import com.myplus.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Notification API (slice 33, Phase 8). Other services POST here instead of owning SMTP. Returns the raw
 * boolean (sent) so the NotificationClient deserializes it directly.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping("/email")
    public boolean email(@RequestBody EmailRequest request) {
        return notificationService.sendEmail(request);
    }
}
