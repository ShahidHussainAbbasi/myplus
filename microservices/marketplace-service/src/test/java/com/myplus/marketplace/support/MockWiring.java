package com.myplus.marketplace.support;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Fails a test the moment {@code @InjectMocks} has left a collaborator null — instead of three slices later,
 * as an NPE with no clue in it.
 *
 * <h3>Why this exists</h3>
 * {@code @InjectMocks} fills what it can and puts {@code null} in the rest, silently. So adding a constructor
 * dependency to a service does not break its tests at COMPILE time and does not warn at RUN time; it breaks
 * whichever test first walks a path that dereferences the new field, with a message that names the field but
 * not the reason.
 *
 * <p>This codebase has now paid for that at least five times — three on {@code SagaSellService}, once when
 * {@code DispatchInvoiceService} was added to {@code ShipmentService} (see the comment its test still carries),
 * and again when {@code OrderStockHoldService} was added for O7 D1c and took out 6 tests across two classes.
 * The pattern is documented in {@code CustomerImportSpecTest}'s javadoc as "this codebase's recurring trap".
 *
 * <h3>What it checks, and why that set</h3>
 * Only non-static FINAL instance fields — which is exactly the constructor-injected set for a
 * {@code @RequiredArgsConstructor} service, and excludes loggers, constants and mutable state. A field in that
 * set being null means the test's mock list has fallen behind the service's constructor.
 *
 * <p>It reports EVERY missing collaborator at once, not the first: being told about one, adding it, re-running
 * and being told about the next is the same bad afternoon the import engine's multi-error rows exist to avoid.
 */
public final class MockWiring {

    private MockWiring() {}

    /** @throws AssertionError naming every unmocked collaborator, with the field to add */
    public static void assertFullyWired(Object service) {
        if (service == null) throw new AssertionError("The service under test is itself null — @InjectMocks did not run.");

        List<String> missing = new ArrayList<>();
        for (Class<?> c = service.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                try {
                    if (f.get(service) == null) missing.add(f.getType().getSimpleName() + " " + f.getName());
                } catch (IllegalAccessException e) {
                    throw new AssertionError("Cannot read " + f.getName() + " on " + c.getSimpleName(), e);
                }
            }
        }

        if (!missing.isEmpty()) {
            throw new AssertionError(service.getClass().getSimpleName()
                    + " has unmocked collaborators, so @InjectMocks left them null and any path touching one "
                    + "will NPE with no explanation. Add a @Mock field for each:\n  - "
                    + String.join("\n  - ", missing));
        }
    }
}
