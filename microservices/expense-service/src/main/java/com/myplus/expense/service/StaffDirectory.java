package com.myplus.expense.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.myplus.common.web.exception.ValidationException;

/**
 * EX-7b — the business's STAFF, from auth-service (its own member list, scoped by the caller's token): the people an
 * advance may be given to. A guardian or a student is a member of a school but never staff, so never a recipient.
 */
@Service
public class StaffDirectory {

    /** Roles that work for the business. Everything else (GUARDIAN, STUDENT, …) is not staff. */
    static final Set<String> STAFF = Set.of("OWNER", "ADMIN", "USER");

    /**
     * {@code active} = false for a member the owner REMOVED (TEAM-1, membership INACTIVE): still named on their past
     * expenses, never offered for a new advance.
     */
    public record Member(Long userId, String name, String email, String role, boolean active) {
        public Member(Long userId, String name, String email, String role) { this(userId, name, email, role, true); }
    }

    private final RestClient auth;

    public StaffDirectory(@Qualifier("authRestClient") RestClient auth) {
        this.auth = auth;
    }

    /** The staff of the caller's business; the caller must be an owner or admin (auth refuses anyone else). */
    @SuppressWarnings("unchecked")
    public List<Member> staff() {
        Map<String, Object> body;
        try {
            body = auth.get().uri("/api/auth/org/users").retrieve().body(Map.class);
        } catch (Exception e) {
            throw new ValidationException("The list of staff could not be read right now. Try again in a moment.");
        }
        Object data = body == null ? null : body.get("data");
        List<Member> out = new ArrayList<>();
        if (!(data instanceof List<?> rows)) return out;
        for (Object o : rows) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String role = m.get("role") == null ? "" : String.valueOf(m.get("role")).toUpperCase();
            Object id = m.get("userId");
            if (!STAFF.contains(role) || id == null) continue;
            if (Boolean.FALSE.equals(m.get("enabled"))) continue;
            String name = m.get("name") == null || String.valueOf(m.get("name")).isBlank() ? String.valueOf(m.get("email")) : String.valueOf(m.get("name"));
            boolean active = !"INACTIVE".equalsIgnoreCase(String.valueOf(m.get("status")));   // TEAM-1: removed = inactive
            out.add(new Member(Long.valueOf(String.valueOf(id)), name, m.get("email") == null ? null : String.valueOf(m.get("email")), role, active));
        }
        return out;
    }

    /** TEAM-1 — the staff who may be CHOSEN now (a removed member is not offered). Past rows use {@link #staff()}. */
    public List<Member> activeStaff() {
        return staff().stream().filter(Member::active).toList();
    }

    /** This member, if they are ACTIVE staff of the caller's business (an advance is never given to someone removed). */
    public Optional<Member> staffMember(Long userId) {
        if (userId == null) return Optional.empty();
        return activeStaff().stream().filter(m -> m.userId().equals(userId)).findFirst();
    }
}
