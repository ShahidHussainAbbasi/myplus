package com.myplus.marketplace.support;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.myplus.common.security.GatewayIdentityForwarding;

/**
 * Stamp the tenant on an outbound service-to-service call.
 *
 * <h3>Why this is a class rather than a private method</h3>
 * It was a private method — <b>twice</b>, character-for-character, in {@code DispatchInvoiceService} and
 * {@code DeliveryService}, and O7 D5 wanted a third. The platform's rule is that the same function is never
 * defined in two files, and the reason bites here specifically: this helper decides <b>which identity another
 * service sees</b>, so a divergence between copies would be a divergence in who a downstream write is
 * attributed to — and only on whichever path happened not to be edited.
 *
 * <h3>Why the user id is 0</h3>
 * The acting person's identity does not travel across the boundary; the TENANT does. business-service
 * authenticates the org and re-checks it against anything the body claims, which is what stops an in-network
 * caller booking another tenant's revenue. Sending a real user id would imply an authority the receiving
 * service has no way to verify.
 */
public final class AsOrg {

    /** The system caller. Not a person — see the class javadoc. */
    private static final Long SERVICE_USER = 0L;

    private AsOrg() { }

    /** Run {@code call} with the tenant stamped on every outbound request it makes, and return its value. */
    public static <T> T call(Long organizationId, Supplier<T> call) {
        AtomicReference<T> out = new AtomicReference<>();
        GatewayIdentityForwarding.runAs(SERVICE_USER, organizationId, () -> out.set(call.get()));
        return out.get();
    }

    /** The void form. */
    public static void run(Long organizationId, Runnable action) {
        GatewayIdentityForwarding.runAs(SERVICE_USER, organizationId, action);
    }
}
