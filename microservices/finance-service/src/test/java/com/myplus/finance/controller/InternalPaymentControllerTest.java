package com.myplus.finance.controller;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.finance.dto.RecordPaymentRequest;
import com.myplus.finance.entity.PartyType;
import com.myplus.finance.entity.PaymentDirection;
import com.myplus.finance.service.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * BLK-0a — the ledger WRITE is internal-only, and it refuses a request that names no tenant.
 *
 * <p>No Spring, no DB. The live proof that the gateway refuses the old path is the Cypress gate
 * ({@code cypress/e2e/security/finance-ledger-write-guard.cy.js}); what is locked in HERE is the part that
 * would regress silently in a code review — somebody "tidying" the write back onto the public controller, or
 * dropping the missing-tenant refusal because every caller happens to send one today.
 */
class InternalPaymentControllerTest {

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theWriteLivesUnderInternal_whichNoGatewayRouteMatches() {
        RequestMapping mapping = InternalPaymentController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, "InternalPaymentController must declare its base path");
        assertTrue(mapping.value().length > 0
                        && Arrays.stream(mapping.value()).allMatch(p -> p.startsWith("/internal/")),
                "the write must stay under /internal/** — that prefix is what the gateway does not route");
    }

    @Test
    void thePublicControllerCarriesNoWrite() {
        // /api/finance/** IS gateway-routed. Any write mapped here is reachable by any holder of a JWT, which
        // is the exact gap BLK-0 closed. Reads stay: they are tenant-scoped and statements depend on them.
        for (Method m : PaymentController.class.getDeclaredMethods()) {
            String where = "PaymentController." + m.getName();
            assertNull(m.getAnnotation(PostMapping.class), where + " must not be a POST");
            assertNull(m.getAnnotation(PutMapping.class), where + " must not be a PUT");
            assertNull(m.getAnnotation(PatchMapping.class), where + " must not be a PATCH");
            assertNull(m.getAnnotation(DeleteMapping.class), where + " must not be a DELETE");
            RequestMapping rm = m.getAnnotation(RequestMapping.class);
            if (rm != null) {
                assertTrue(rm.method().length > 0
                                && Arrays.stream(rm.method()).allMatch(x -> x == RequestMethod.GET),
                        where + ": a bare @RequestMapping accepts every verb, POST included");
            }
        }
    }

    @Test
    void aRequestWithNoTenant_isRefused_andNothingIsWritten() {
        // Unauthenticated, and authenticated-without-an-org: both would otherwise write a NULL-org ledger row,
        // which the NULL-fallback leg of every scoped read in this service would then surface.
        PaymentService svc = mock(PaymentService.class);
        InternalPaymentController controller = new InternalPaymentController(svc);

        assertThrows(IllegalStateException.class, () -> controller.record(request()));

        authenticateAs(null);
        assertThrows(IllegalStateException.class, () -> controller.record(request()));

        verifyNoInteractions(svc);
    }

    @Test
    void aTenantScopedRequest_isRecorded() {
        authenticateAs(7L);
        PaymentService svc = mock(PaymentService.class);
        RecordPaymentRequest req = request();

        new InternalPaymentController(svc).record(req);

        verify(svc).record(req);
    }

    private static RecordPaymentRequest request() {
        return RecordPaymentRequest.builder()
                .direction(PaymentDirection.RECEIPT)
                .partyType(PartyType.CUSTOMER).partyId(1L)
                .amount(new BigDecimal("100.00"))
                .build();
    }

    private static void authenticateAs(Long organizationId) {
        AuthenticatedUser user = new AuthenticatedUser(1L, "cashier@test", List.of(), organizationId);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
