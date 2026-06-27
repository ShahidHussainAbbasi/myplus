package com.myplus.education.util;

import com.myplus.common.security.AuthenticatedUser;
import com.myplus.common.security.CurrentUser;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Thin adapter over the shared {@link CurrentUser} accessor (slice 33, Phase 2b). Kept as an injectable
 * bean so existing controllers keep working unchanged; new code should use {@link CurrentUser} directly.
 */
@Component
public class RequestUtil {

    @Nullable
    public AuthenticatedUser getCurrentUser() {
        return CurrentUser.get().orElse(null);
    }
}
