package com.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.service.IUserService;
import com.service.LiveUserCountService;

/**
 * Pure-logic tests for the "users online" figure — no Spring context, so they run on every
 * {@code mvn test}.
 *
 * The point of the multiplier living behind a property is that setting it to 1 restores the honest
 * count with no code change; the first two tests are what keep that true.
 */
@ExtendWith(MockitoExtension.class)
class LiveUserCountServiceTest {

    @Mock
    private IUserService userService;

    @InjectMocks
    private LiveUserCountService liveUserCountService;

    @BeforeEach
    void setUp() {
        // @Value fields are not populated outside a Spring context.
        setMultiplier(5);
        setEnabled(true);
    }

    private void setMultiplier(int value) {
        ReflectionTestUtils.setField(liveUserCountService, "multiplier", value);
    }

    private void setEnabled(boolean value) {
        ReflectionTestUtils.setField(liveUserCountService, "enabled", value);
    }

    @Test
    void appliesTheConfiguredMultiplierToTheRealCount() {
        when(userService.getLoggedInUserCount()).thenReturn(3);

        assertEquals(15, liveUserCountService.getDisplayCount(), "3 signed-in users × 5");
    }

    @Test
    void multiplierOfOnePublishesTheTrueCount() {
        setMultiplier(1);
        when(userService.getLoggedInUserCount()).thenReturn(7);

        assertEquals(7, liveUserCountService.getDisplayCount(),
                "setting the property to 1 must restore the honest figure");
    }

    @Test
    void actualCountIsNeverMultiplied() {
        when(userService.getLoggedInUserCount()).thenReturn(4);

        assertEquals(4, liveUserCountService.getActualCount(),
                "the true count must stay clean — anything operational reads this");
    }

    @Test
    void reportsZeroWhenNobodyIsSignedIn() {
        when(userService.getLoggedInUserCount()).thenReturn(0);

        // 0 × 5 is still 0 — the pages hide the badge rather than advertising an empty product.
        assertEquals(0, liveUserCountService.getDisplayCount());
    }

    @Test
    void reportsZeroWhenDisabledWithoutTouchingTheRegistry() {
        setEnabled(false);

        assertEquals(0, liveUserCountService.getDisplayCount());
        verify(userService, times(0)).getLoggedInUserCount();
    }

    @Test
    void aNonPositiveMultiplierFallsBackToTheTrueCountRatherThanZero() {
        setMultiplier(0);
        when(userService.getLoggedInUserCount()).thenReturn(6);

        assertEquals(6, liveUserCountService.getDisplayCount(),
                "a misconfigured multiplier must not erase the badge");
    }

    @Test
    void cachesSoAPublicEndpointCannotRecomputePerHit() {
        when(userService.getLoggedInUserCount()).thenReturn(2);

        for (int i = 0; i < 25; i++) {
            assertEquals(10, liveUserCountService.getDisplayCount());
        }

        verify(userService, times(1)).getLoggedInUserCount();
    }
}
