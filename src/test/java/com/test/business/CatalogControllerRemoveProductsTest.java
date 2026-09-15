package com.test.business;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.HttpClientErrorException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.controller.business.CatalogController;
import com.web.error.DownstreamNotFoundException;
import com.web.util.CatalogRestClient;

/**
 * PROD-DEL — the Product screen's Delete ({@code POST /removeProducts}), without a Spring context so it runs on
 * every {@code mvn test}.
 *
 * <p>The first case is the regression. {@code CatalogRestClient} goes through {@code GatewayClient}, which turns
 * every downstream 404 into {@link DownstreamNotFoundException}; {@code removeProducts} caught only Spring's
 * {@code HttpClientErrorException.NotFound}, so its "already removed" branch could never run and a repeat delete
 * told the owner "Kept: #6200 (Could not be removed. Please try again.)". The other two pin what the widened catch
 * must NOT change: an active product is still deactivated, and a real refusal still comes back as kept, with
 * catalog's own sentence.
 */
@ExtendWith(MockitoExtension.class)
class CatalogControllerRemoveProductsTest {

    @Mock
    private CatalogRestClient catalog;

    @InjectMocks
    private CatalogController controller;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aProductAlreadyGoneIsReportedAsAlreadyRemovedNotKept() {
        // Exactly what the gateway raises for catalog's 404 on a product deleted a moment ago.
        when(catalog.get("/products/6200"))
                .thenThrow(new DownstreamNotFoundException("{\"success\":false,\"message\":\"Product not found: 6200\"}"));

        Map<String, Object> out = controller.removeProducts(Map.of("checked", "6200"));

        assertEquals(true, out.get("success"));
        assertEquals(1, out.get("alreadyRemoved"));
        assertEquals(0, out.get("deleted"));
        assertEquals(List.of(), out.get("kept"), "a product that no longer exists must not be reported as kept");
        assertEquals("1 already removed.", out.get("message"));
        verify(catalog, never()).delete(anyString());
    }

    @Test
    void anActiveProductIsStillDeactivated() {
        when(catalog.get("/products/7")).thenReturn(Map.of("data", Map.of("name", "Panadol", "isActive", true)));

        Map<String, Object> out = controller.removeProducts(Map.of("checked", "7"));

        assertEquals(1, out.get("deactivated"));
        assertEquals(0, out.get("alreadyRemoved"));
        verify(catalog).putJson(eq("/products/7/deactivate"), any());
        verify(catalog, never()).delete(anyString());
    }

    @Test
    void aRefusedPermanentDeleteIsStillKeptWithCatalogsSentence() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("owner", "n/a", "ROLE_OWNER"));
        when(catalog.get("/products/8")).thenReturn(Map.of("data", Map.of("name", "Stocked", "isActive", false)));
        String refusal = "\"Stocked\" is kept: it is still used by 1 stock level record.";
        byte[] body = new ObjectMapper()
                .writeValueAsString(Map.of("success", false, "message", refusal))
                .getBytes(StandardCharsets.UTF_8);
        // A refusal is a 400 carrying the sentence — not a 404, so the widened catch must not swallow it.
        when(catalog.delete("/products/8")).thenThrow(HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, body, StandardCharsets.UTF_8));

        Map<String, Object> out = controller.removeProducts(Map.of("checked", "8"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kept = (List<Map<String, Object>>) out.get("kept");
        assertEquals(1, kept.size());
        assertEquals(refusal, kept.get(0).get("reason"));
        assertEquals(0, out.get("deleted"));
        assertEquals(0, out.get("alreadyRemoved"));
    }
}
