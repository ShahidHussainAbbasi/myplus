package com.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * PERM-1 / RW-0c — the endpoint rules restrict what they name, the prefix ORDER holds, and the refusal quotes the
 * catalogue's label. The rules here are rows as auth-service serves them (endpoint_permission, V26, in its order);
 * since RW-0c the interceptor holds none of its own.
 *
 * <p>Rules match by PATH PREFIX, so {@code /addProduct} also matches {@code /addProductStock}: adding stock must ask for
 * "Adjust stock in", not "Add a product". Paths with no row stay open.
 *
 * <p>Plain JUnit, no Spring context, no DB — runs on every {@code mvn test}.
 */
class PermissionInterceptorTest {

    /** The V26 rows that matter here, in their order (stock before product). */
    private static final List<PermissionInterceptor.Rule> ROWS = List.of(
            new PermissionInterceptor.Rule("POST", "/addPurchase", "purchase.create", "Receive stock"),
            new PermissionInterceptor.Rule("POST", "/addProductStock", "stock.create", "Adjust stock in"),
            new PermissionInterceptor.Rule("POST", "/addProduct", "product.create", "Add a product"));

    private PermissionInterceptor interceptor = new PermissionInterceptor(() -> ROWS);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse call(String method, String path, String... authorities) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "member@test", "n/a",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).collect(Collectors.toList())));
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean passed = interceptor.preHandle(new MockHttpServletRequest(method, path), res, null);
        assertEquals(passed, res.getStatus() == 200, "a pass must not also write a refusal, and vice versa");
        return res;
    }

    private MockHttpServletResponse post(String path, String... authorities) throws Exception {
        return call("POST", path, authorities);
    }

    @Test
    void addingStock_needsStockCreate_notProductCreate() throws Exception {
        assertEquals(403, post("/addProductStock", "product.create").getStatus(),
                "being allowed to add products is not permission to put stock on the shelf");
        assertEquals(200, post("/addProductStock", "stock.create").getStatus());
    }

    @Test
    void theRefusal_quotesTheCataloguesLabel_andSaysWhoCanFixIt() throws Exception {
        String body = post("/addPurchase", "sale.create").getContentAsString();
        assertTrue(body.contains("“Receive stock”"), body);
        assertTrue(body.contains("Ask the shop owner to change your permission set on Team"), body);
        assertTrue(body.contains("\"success\":false"), body);
    }

    @Test
    void addingAProduct_stillNeedsProductCreate() throws Exception {
        assertEquals(403, post("/addProduct", "stock.create").getStatus());
        assertEquals(200, post("/addProduct", "product.create").getStatus());
    }

    @Test
    void theOwner_holdsEveryPermission_withoutARowSayingSo() throws Exception {
        assertEquals(200, post("/addProductStock", "ROLE_OWNER").getStatus());
    }

    @Test
    void aPathWithNoRow_staysOpen() throws Exception {
        assertEquals(200, post("/adjustProductStock", "product.view").getStatus());
        assertEquals(200, post("/setProductTracking", "product.create").getStatus());
    }

    @Test
    void aSignedOutCaller_isRefusedAMappedWrite() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new MockHttpServletRequest("POST", "/addProductStock"), res, null));
        assertEquals(403, res.getStatus());
    }

    // ── RW-0c option (a): the rules not loaded yet ─────────────────────────────────────────────────────────────────

    @Test
    void rulesNotLoaded_aWriteWaits_withAPlainMessage_andNothingIsSaved() throws Exception {
        interceptor = new PermissionInterceptor(() -> null);
        MockHttpServletResponse res = post("/addSell", "sale.create");
        assertEquals(503, res.getStatus());
        assertTrue(res.getContentAsString().contains("Permissions are still loading"), res.getContentAsString());
        assertTrue(res.getContentAsString().contains("Nothing was saved"), res.getContentAsString());
    }

    @Test
    void rulesNotLoaded_aReadPasses() throws Exception {
        interceptor = new PermissionInterceptor(() -> null);
        assertEquals(200, call("GET", "/team/users", "sale.view").getStatus());
    }

    @Test
    void rulesNotLoaded_theAlwaysOpenPathsStillPass() throws Exception {
        interceptor = new PermissionInterceptor(() -> null);
        assertEquals(200, post("/login").getStatus());
    }
}
