package com.web.controller.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * MKT-1e checkout proxy: a refusal and an UNKNOWN outcome must read differently on the wire. The page keeps its
 * attempt key only for UNKNOWN — so a timeout read as a refusal let a retry place a second order.
 */
class MarketplacePublicControllerTest {

    private static final String URL = "http://gw/api/marketplace/public/mkt/checkout";
    private MockRestServiceServer server;
    private MarketplacePublicController controller;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        controller = new MarketplacePublicController("http://gw", builder.build());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> checkout() {
        return (Map<String, Object>) controller.checkout(Map.of("offerId", 1, "idempotencyKey", "k1"), new org.springframework.mock.web.MockHttpServletRequest());
    }

    @Test
    @DisplayName("[MKT-R22.3] a read timeout is UNKNOWN, not a refusal: the page keeps its key and a retry returns the same order")
    void timeoutIsUnknown() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
        Map<String, Object> r = checkout();
        assertThat(r).containsEntry("success", false).containsEntry("outcome", "UNKNOWN");
        assertThat((String) r.get("message")).contains("will not be placed twice");
    }

    @Test
    @DisplayName("[MKT-R22.3] a gateway 504 is UNKNOWN too: the order may have been placed behind it")
    void gateway5xxIsUnknown() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));
        assertThat(checkout()).containsEntry("outcome", "UNKNOWN");
    }

    @Test
    @DisplayName("[MKT-R22.2] a 4xx is the service's answer: relayed as a refusal with its own sentence, never UNKNOWN")
    void refusalIsARefusal() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"success\":false,\"message\":\"This seller no longer has enough stock. Please choose another offer.\"}"));
        Map<String, Object> r = checkout();
        assertThat(r).containsEntry("success", false).doesNotContainKey("outcome");
        assertThat(r.get("message")).isEqualTo("This seller no longer has enough stock. Please choose another offer.");
    }

    @Test
    @DisplayName("positive control: a placed order is relayed as it came")
    void placedIsRelayed() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"orderNo\":\"MKT-000001\"}}", MediaType.APPLICATION_JSON));
        assertThat(checkout()).containsEntry("success", true).doesNotContainKey("outcome");
    }

    @Test
    @DisplayName("[MKT-R7.6] search text is data in the URL: braces, plus, ampersand and percent are encoded, never a template")
    void strictEncoding() {
        java.net.URI u = MarketplacePublicController.uri("http://gw", "/api/marketplace/public/mkt/products", Map.of(),
                new java.util.LinkedHashMap<>(Map.of("q", "%{enter}+a&b=c")));
        assertThat(u.toString()).isEqualTo("http://gw/api/marketplace/public/mkt/products?q=%25%7Benter%7D%2Ba%26b%3Dc");
        java.net.URI o = MarketplacePublicController.uri("http://gw", "/api/marketplace/public/mkt/orders/{no}", Map.of("no", "MKT-1/{x}"),
                new java.util.LinkedHashMap<>(Map.of("phone", "0300 1234567")));
        assertThat(o.toString()).isEqualTo("http://gw/api/marketplace/public/mkt/orders/MKT-1%2F%7Bx%7D?phone=0300%201234567");
    }

    @Test
    @DisplayName("[MKT-R7.6] a search for \"%{enter}\" reaches the service as that text (it was a 500 before)")
    void braceSearchRelayed() {
        server.expect(requestTo("http://gw/api/marketplace/public/mkt/products?q=%25%7Benter%7D&city=Karachi"))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"content\":[]}}", MediaType.APPLICATION_JSON));
        @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) controller.search("%{enter}", "Karachi", null, null);
        assertThat(r).containsEntry("success", true);
    }
    @Test
    @DisplayName("[MKT-R22.3] sign-in: the token goes into an HttpOnly cookie and never reaches the page")
    void signInCookie() {
        server.expect(requestTo("http://gw/api/marketplace/public/mkt/account/login")).andRespond(withSuccess(
                "{\"success\":true,\"data\":{\"token\":\"T0K3N\",\"customerId\":5,\"name\":\"Ali\",\"phone\":\"03111234567\"}}",
                MediaType.APPLICATION_JSON));
        org.springframework.mock.web.MockHttpServletResponse res = new org.springframework.mock.web.MockHttpServletResponse();
        @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) controller.login(Map.of("phone", "x", "password", "y"),
                new org.springframework.mock.web.MockHttpServletRequest(), res);
        assertThat(String.valueOf(r)).doesNotContain("T0K3N");
        String cookie = res.getHeader("Set-Cookie");
        assertThat(cookie).contains("MKT_SESSION=T0K3N").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/marketplace");
    }

    @Test
    @DisplayName("[MKT-R1.1] a signed-in checkout goes to the account route with the session header")
    void signedInCheckout() {
        server.expect(requestTo("http://gw/api/marketplace/public/mkt/account/checkout"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("X-Mkt-Session", "S1"))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"orderNo\":\"MKT-000002\"}}", MediaType.APPLICATION_JSON));
        org.springframework.mock.web.MockHttpServletRequest req = new org.springframework.mock.web.MockHttpServletRequest();
        req.setCookies(new jakarta.servlet.http.Cookie("MKT_SESSION", "S1"));
        @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) controller.checkout(Map.of("offerId", 1), req);
        assertThat(r).containsEntry("success", true);
    }

    @Test
    @DisplayName("[MKT-R22.3] sign-out clears the cookie in the browser")
    void signOutClears() {
        server.expect(requestTo("http://gw/api/marketplace/public/mkt/account/logout"))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));
        org.springframework.mock.web.MockHttpServletResponse res = new org.springframework.mock.web.MockHttpServletResponse();
        controller.logout(new org.springframework.mock.web.MockHttpServletRequest(), res);
        assertThat(res.getHeader("Set-Cookie")).contains("MKT_SESSION=").contains("Max-Age=0");
    }
}
