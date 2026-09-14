package com.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * PERM-1 — the product-area rules restrict what they name, and the prefix order holds.
 *
 * <p>Rules match by PATH PREFIX, so {@code /addProduct} also matches {@code /addProductStock}. These cases pin
 * that adding stock asks for "Adjust stock in" rather than "Add a product", that adding a product still asks for
 * {@code product.create}, and that the paths deliberately left open stay open.
 *
 * <p>The {@code product.edit} rules are on HOLD (see PermissionInterceptor), so nothing here asserts them.
 *
 * <p>Plain JUnit over the interceptor itself, with no Spring context and no DB, so it runs on every
 * {@code mvn test}.
 */
class PermissionInterceptorTest {

    private final PermissionInterceptor interceptor = new PermissionInterceptor();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** POST {@code path} as a signed-in member holding exactly these authorities; returns the response. */
    private MockHttpServletResponse post(String path, String... authorities) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "member@test", "n/a",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).collect(Collectors.toList())));
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean passed = interceptor.preHandle(new MockHttpServletRequest("POST", path), res, null);
        assertEquals(passed, res.getStatus() == 200, "a pass must not also write a refusal, and vice versa");
        return res;
    }

    @Test
    void addingStock_needsStockCreate_notProductCreate() throws Exception {
        // Pins the ORDER: below the "/addProduct" prefix, /addProductStock asked for "Add a product".
        assertEquals(403, post("/addProductStock", "product.create").getStatus(),
                "being allowed to add products is not permission to put stock on the shelf");
        assertEquals(200, post("/addProductStock", "stock.create").getStatus());
        assertTrue(post("/addProductStock", "product.view").getContentAsString().contains("add stock"),
                "the refusal names the action");
    }

    @Test
    void addingAProduct_stillNeedsProductCreate() throws Exception {
        MockHttpServletResponse refused = post("/addProduct", "stock.create");
        assertEquals(403, refused.getStatus());
        assertTrue(refused.getContentAsString().contains("add products"), refused.getContentAsString());
        assertEquals(200, post("/addProduct", "product.create").getStatus());
    }

    @Test
    void theOwner_holdsEveryPermission_withoutARowSayingSo() throws Exception {
        assertEquals(200, post("/addProductStock", "ROLE_OWNER").getStatus());
    }

    @Test
    void correctingStock_andTrackingFlags_stayUnmapped_soNobodyLosesThem() throws Exception {
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
}
