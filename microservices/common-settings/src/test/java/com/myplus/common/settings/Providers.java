package com.myplus.common.settings;

import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Test-only {@link ObjectProvider}s, so a unit test can build a {@link SettingsService} without a Spring
 * context.
 *
 * <p>Exists because {@code ObjectProvider} cannot be written as a lambda for this purpose: the default
 * {@code orderedStream()} delegates to {@code stream()}, whose default implementation throws. A test passing a
 * lambda would compile, run, and silently exercise a chain that is not there — the same class of
 * invisible-inertness this module has already been bitten by twice.
 *
 * <p>Generic since E4 added {@link SettingWriteListener} beside {@link SettingWriteGuard}. It was the
 * guard-only {@code Guards} helper until the second collaborator arrived; copying it would have been the
 * duplication the DRY rule names, in the one place a test is least likely to be read.
 */
final class Providers {

    private Providers() { }

    /** Nothing registered — the state of every service that owns neither an entitlement store nor an audit producer. */
    static <T> ObjectProvider<T> none() {
        return of();
    }

    /** The given beans, in order. */
    @SafeVarargs
    static <T> ObjectProvider<T> of(T... beans) {
        List<T> list = Arrays.asList(beans);
        return new ObjectProvider<>() {
            @Override public Stream<T> stream() { return list.stream(); }
            @Override public Stream<T> orderedStream() { return list.stream(); }
            @Override public T getObject() { throw new UnsupportedOperationException(); }
            @Override public T getObject(Object... args) { throw new UnsupportedOperationException(); }
            @Override public T getIfAvailable() { return list.isEmpty() ? null : list.get(0); }
            @Override public T getIfUnique() { return list.size() == 1 ? list.get(0) : null; }
        };
    }
}
