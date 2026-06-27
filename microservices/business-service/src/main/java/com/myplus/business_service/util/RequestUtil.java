package com.myplus.business_service.util;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.security.CurrentUser;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Thin adapter over the shared {@link CurrentUser} accessor (slice 33, Phase 2b). Kept as an injectable
 * bean so existing controllers/services keep working unchanged; new code should use {@link CurrentUser}
 * directly. The previous per-service copy had diverged identity-reading logic plus dead helpers — all
 * removed in favour of the single source of truth.
 */
@Component
public class RequestUtil {

    @Nullable
    public AuthenticatedUser getCurrentUser() {
        return CurrentUser.get().orElse(null);
    }
}
