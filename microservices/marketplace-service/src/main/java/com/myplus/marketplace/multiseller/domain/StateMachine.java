package com.myplus.marketplace.multiseller.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * An explicit allow-list of moves for one status family — the {@code FulfilmentStatus.ALLOWED} pattern (OMS O2),
 * generalised so the four marketplace lifecycles share one implementation instead of four hand-written switches.
 *
 * <p>Anything not listed is refused. The failure mode of a missed illegal move is money or goods moving against
 * a reversed order, so the safe default is "no".
 */
public final class StateMachine<E extends Enum<E>> {

    private final Class<E> type;
    private final Map<E, Set<E>> moves;

    private StateMachine(Class<E> type, Map<E, Set<E>> moves) {
        this.type = type;
        this.moves = moves;
    }

    public static <E extends Enum<E>> Builder<E> of(Class<E> type) {
        return new Builder<>(type);
    }

    public boolean canTransition(E from, E to) {
        if (from == null || to == null) return false;
        Set<E> allowed = moves.get(from);
        return allowed != null && allowed.contains(to);
    }

    /** Returns {@code to}, or refuses with {@code INVALID_TRANSITION} naming both states. */
    public E transition(E from, E to) {
        if (!canTransition(from, to))
            throw new MarketplaceRuleException("INVALID_TRANSITION",
                    "This " + label() + " cannot move from " + from + " to " + to + ".");
        return to;
    }

    /** The moves a UI may offer — published so the client never keeps its own copy (OMS O4). */
    public Set<E> allowedFrom(E from) {
        Set<E> allowed = moves.get(from);
        return allowed == null ? EnumSet.noneOf(type) : Collections.unmodifiableSet(EnumSet.copyOf(allowed));
    }

    public boolean isTerminal(E state) {
        return allowedFrom(state).isEmpty();
    }

    private String label() {
        return type.getSimpleName().replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
    }

    public static final class Builder<E extends Enum<E>> {
        private final Class<E> type;
        private final Map<E, Set<E>> moves;

        private Builder(Class<E> type) {
            this.type = type;
            this.moves = new EnumMap<>(type);
        }

        @SafeVarargs
        public final Builder<E> allow(E from, E... to) {
            Set<E> set = moves.computeIfAbsent(from, k -> EnumSet.noneOf(type));
            Collections.addAll(set, to);
            return this;
        }

        public StateMachine<E> build() {
            Map<E, Set<E>> frozen = new EnumMap<>(type);
            moves.forEach((k, v) -> frozen.put(k, Collections.unmodifiableSet(EnumSet.copyOf(v))));
            return new StateMachine<>(type, Collections.unmodifiableMap(frozen));
        }
    }
}
