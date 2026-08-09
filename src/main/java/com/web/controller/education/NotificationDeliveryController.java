package com.web.controller.education;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.GatewayClient;

/**
 * Slice 105 — reading back what was actually delivered.
 *
 * <p>Without this the new read endpoints would be unreachable from the application: a capability that
 * exists in the service and answers nobody. That is the failure mode the OMS work named a "dead toggle",
 * and a delivery record that cannot be read is exactly it — the whole point of the slice is answering "did
 * that family get the closure notice?".
 *
 * <p><b>No identity is passed from here.</b> The tenant is taken from the JWT by the gateway and enforced
 * inside notification-service; this class forwards a recipient and nothing more. Accepting an orgId here
 * would let a caller name someone else's tenant — the anti-IDOR rule the platform applies everywhere.
 */
@Controller
public class NotificationDeliveryController {

    /** Gateway route is un-stripped, so the service's full path is part of the prefix. */
    private static final String PREFIX = "/api/notifications";

    /** Direct (no-gateway) mode needs the same full path baked into the base URL. */
    @Value("${notification.service.url:http://localhost:8093}/api/notifications")
    private String directBaseUrl;

    @Autowired
    private GatewayClient gateway;

    /**
     * What this address was sent, newest first. The support question, answered.
     */
    @RequestMapping(value = "/getNotificationDeliveries", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> deliveries(@RequestParam String recipient,
                                             @RequestParam(required = false, defaultValue = "50") int limit) {
        return gateway.forStringEntity(PREFIX, directBaseUrl,
                "/deliveries?recipient=" + java.net.URLEncoder.encode(recipient,
                        java.nio.charset.StandardCharsets.UTF_8) + "&limit=" + limit,
                HttpMethod.GET, null, null);
    }

    /**
     * The outcome of one broadcast — "sent to 298, 2 failed, here are the 2".
     */
    @RequestMapping(value = "/getNotificationBroadcast", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<String> broadcast(@RequestParam Long id) {
        return gateway.forStringEntity(PREFIX, directBaseUrl, "/broadcasts/" + id,
                HttpMethod.GET, null, null);
    }
}
