package com.myplus.common.settings;

import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Test-only {@link ObjectProvider}s of {@link SettingWriteGuard}, so a unit test can build a
 * {@link SettingsService} without a Spring context.
 *
 * <p>Exists because {@code ObjectProvider} cannot be written as a lambda for this purpose: the default
 * {@code orderedStream()} delegates to {@code stream()}, whose default implementation throws. A test passing a
 * lambda would compile, run, and silently exercise a guard chain that is not there — the same class of
 * invisible-inertness this module has already been bitten by twice.
 */
final class Guards {

    private Guards() { }

    /** No guards at all — the state of every service that does not own an entitlement store. */
    static ObjectProvider<SettingWriteGuard> none() {
        return of();
    }

    /** The given guards, in order. */
    static ObjectProvider<SettingWriteGuard> of(SettingWriteGuard... guards) {
        List<SettingWriteGuard> list = Arrays.asList(guards);
        return new ObjectProvider<>() {
            @Override public Stream<SettingWriteGuard> stream() { return list.stream(); }
            @Override public Stream<SettingWriteGuard> orderedStream() { return list.stream(); }
            @Override public SettingWriteGuard getObject() { throw new UnsupportedOperationException(); }
            @Override public SettingWriteGuard getObject(Object... args) { throw new UnsupportedOperationException(); }
            @Override public SettingWriteGuard getIfAvailable() { return list.isEmpty() ? null : list.get(0); }
            @Override public SettingWriteGuard getIfUnique() { return list.size() == 1 ? list.get(0) : null; }
        };
    }
}
