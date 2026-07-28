package com.service;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The "users online" figure shown on the public landing and login pages.
 *
 * Deliberately separate from {@link IUserService#getLoggedInUserCount()}: that method reports the
 * TRUE number of live sessions and is what any operational use (admin console, monitoring) must
 * read. This class only produces the marketing-facing number, so the display multiplier can never
 * leak into a figure someone makes a decision on.
 *
 * The multiplier is {@code app.live-users.multiplier} (default 5). It is configuration rather than a
 * constant because it was asked for as a temporary presentation choice — setting it to 1 restores
 * the honest count with no code change and no redeploy of anything but the property.
 *
 * Note that a multiplier above 1 publishes a figure that is not the real one. Where the page is
 * seen by prospective customers that is a claim about the product, and several jurisdictions treat
 * an inflated usage statistic as misleading advertising. Set the property to 1 to publish the truth.
 */
@Service
public class LiveUserCountService {

    /** Serve a cached figure for this long. The endpoint is public, so it must not recompute per hit. */
    private static final long CACHE_TTL_MS = 5_000L;

    @Autowired
    private IUserService userService;

    @Value("${app.live-users.multiplier:5}")
    private int multiplier;

    @Value("${app.live-users.enabled:true}")
    private boolean enabled;

    private final AtomicLong cachedAt = new AtomicLong(0L);
    private volatile int cachedCount = 0;

    /**
     * The number to render. Returns 0 when the feature is off or nobody is signed in — the pages
     * hide the badge at 0 rather than advertising an empty product.
     */
    public int getDisplayCount() {
        if (!enabled) {
            return 0;
        }

        final long now = System.currentTimeMillis();
        final long last = cachedAt.get();

        // A racing caller may recompute at the same moment; both produce the same value, so the
        // only cost of losing the CAS is one extra pass over the registry.
        if (now - last > CACHE_TTL_MS && cachedAt.compareAndSet(last, now)) {
            cachedCount = userService.getLoggedInUserCount() * Math.max(1, multiplier);
        }
        return cachedCount;
    }

    /** The real number of signed-in users, unmultiplied — for anything that is not display. */
    public int getActualCount() {
        return userService.getLoggedInUserCount();
    }
}
