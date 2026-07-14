package com.myplus.education.util;

import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.function.Function;

/**
 * Bulk delete with the tenant (and branch) check applied — the ONE implementation every education controller
 * uses, so the rule cannot be forgotten in the next screen someone adds.
 *
 * <p>It exists because every {@code deleteX} endpoint used to do this:
 * <pre>for (String id : ids.split(",")) repo.deleteById(Long.valueOf(id));</pre>
 * The id came straight off the request with no ownership check whatsoever, so any authenticated user could
 * delete <em>another organization's</em> rows just by guessing ids — a cross-tenant IDOR, not merely a
 * cross-branch one. (business-service has always loaded-then-verified; education never did.)
 *
 * <p>A row the caller may not see is skipped <em>silently</em>: reporting "forbidden" versus "not found" would
 * itself confirm that the id exists in someone else's tenant.
 */
@Component
@RequiredArgsConstructor
public class ScopedDeleter {

    private final RequestUtil requestUtil;

    /**
     * Delete the comma-separated {@code ids}, keeping only rows in the caller's tenant and accessible branch.
     *
     * @param locationOf the row's branch (school id), or {@code null} for entities that carry no branch of
     *                   their own — for {@code School} itself pass {@code School::getId}, since the row IS the branch.
     * @return how many rows were actually deleted (callers report success on a well-formed request, as before).
     */
    public <T> int deleteScoped(JpaRepository<T, Long> repo, String ids,
                                Function<T, Long> orgOf, Function<T, Long> userOf,
                                Function<T, Long> locationOf) {
        if (!StringUtils.hasText(ids)) return 0;
        Long callerOrg = orgOf(requestUtil);
        Long callerUser = userOf(requestUtil);
        int deleted = 0;
        for (String raw : ids.split(",")) {
            if (!StringUtils.hasText(raw)) continue;
            Long id;
            try {
                id = Long.valueOf(raw.trim());
            } catch (NumberFormatException notANumber) {
                continue;
            }
            T row = repo.findById(id).orElse(null);
            if (row == null) continue;

            Long rowOrg = orgOf.apply(row);
            Long rowUser = userOf.apply(row);
            boolean myTenant = (rowOrg != null && rowOrg.equals(callerOrg))
                    // legacy: pre-tenancy rows carry no org, so they belong to whoever created them.
                    || (rowOrg == null && rowUser != null && rowUser.equals(callerUser));
            if (!myTenant) continue;
            if (locationOf != null && !requestUtil.canAccessSchool(locationOf.apply(row))) continue;

            repo.delete(row);
            deleted++;
        }
        return deleted;
    }

    private static Long orgOf(RequestUtil requestUtil) {
        var u = requestUtil.getCurrentUser();
        return u == null ? null : u.getOrganizationId();
    }

    private static Long userOf(RequestUtil requestUtil) {
        var u = requestUtil.getCurrentUser();
        return u == null ? null : u.getUserId();
    }
}
