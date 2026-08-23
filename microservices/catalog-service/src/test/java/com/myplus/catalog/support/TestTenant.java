package com.myplus.catalog.support;

import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.security.CurrentUser;

/**
 * The tenant a catalog test runs as — one definition, used by every test that goes through
 * {@code ProductService} rather than straight to the repository.
 *
 * <h3>Why this exists</h3>
 * Both {@code ProductSkuOptionalTest} and {@code ProductLastRatesTest} built their identity by reflection
 * ("to avoid depending on a specific ctor signature"), filling every non-primitive parameter with
 * {@code null}. {@code AuthenticatedUser}'s {@code userId} and {@code organizationId} are both {@code Long},
 * so the caller came out as {@code (null, null)} — and under
 * {@code ProductRepository.SCOPE}:
 *
 * <pre>(p.organizationId = :orgId OR (p.organizationId IS NULL AND p.userId = :userId))</pre>
 *
 * a null on both sides matches NOTHING: {@code = NULL} is UNKNOWN in SQL, never true. Rows written by the
 * test landed in the table and were invisible to every scoped read, so 11 tests failed against working
 * production code. That is precisely the shape the multi-tenancy standard depends on — §6's safety argument
 * ("NULL-org rows only match their own creator") is only true when a real {@code user_id} is present.
 *
 * <h3>Built so a wrong identity is impossible, not merely unlikely</h3>
 * <ul>
 *   <li>The <b>real constructor</b>, not reflection. {@code AuthenticatedUser} has TWO constructors
 *       (an explicit 4-arg one and Lombok's {@code @AllArgsConstructor}), and
 *       {@code getDeclaredConstructors()[0]} has no defined order. Calling it directly means a future
 *       signature change is a COMPILE error here rather than a silent null at run time.</li>
 *   <li>Null org or user is <b>rejected</b>, so no caller can quietly recreate the original bug.</li>
 *   <li>It asserts the <b>property, not the artefact</b>: having set the context, it reads the identity back
 *       through {@link CurrentUser} — the same accessor the service uses. Setting an authentication object
 *       is the artefact; the service being able to see a tenant is the thing that matters.</li>
 * </ul>
 */
public final class TestTenant {

    /** The tenant every catalog test runs as unless it says otherwise. */
    public static final Long ORG = 1L;
    /** The acting user — kept for audit, and the second half of the scoped-read fallback. */
    public static final Long USER = 1L;

    public static final String EMAIL = "test@myplus.com";

    private TestTenant() {}

    /** Authenticate as the default tenant. Call from {@code @BeforeEach}. */
    public static void authenticate() {
        authenticate(ORG, USER);
    }

    /** Authenticate as a specific tenant — for a test that needs a SECOND org to prove isolation. */
    public static void authenticate(Long orgId, Long userId) {
        if (orgId == null || userId == null) {
            throw new IllegalArgumentException(
                    "A test tenant needs a real orgId and userId: a (null, null) caller matches no scoped "
                    + "query, so every row the test writes becomes invisible to the service that wrote it.");
        }

        AuthenticatedUser user = new AuthenticatedUser(userId, EMAIL, List.of(), orgId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));

        // Read it back the way ProductService will. If this ever stops holding, the tests that depend on it
        // must fail HERE with a plain explanation, not fifty lines later as "Product not found: 7".
        if (!orgId.equals(CurrentUser.organizationId()) || !userId.equals(CurrentUser.userId())) {
            throw new IllegalStateException("CurrentUser does not see the test tenant: expected ("
                    + orgId + ", " + userId + ") but got ("
                    + CurrentUser.organizationId() + ", " + CurrentUser.userId() + ").");
        }
    }

    /** Clear the context. Call from {@code @AfterEach} — leave no state behind for the next test. */
    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
