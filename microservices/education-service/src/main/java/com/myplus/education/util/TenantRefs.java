package com.myplus.education.util;

import java.util.Optional;
import java.util.function.Function;

/**
 * EDU-IDOR-2: may a client-supplied id be written onto a record in the caller's tenant?
 *
 * <p>A form posts ids — the row being edited, the school it is filed under, the class, guardian, discount or
 * vehicle it points at. {@link RequestUtil#canAccessSchool} answers only "which BRANCH", never "which
 * ORGANISATION", and is true for an owner and for anyone without location grants. So every such id must be
 * resolved through the repository's {@code findByIdScoped(id, org, uid)} before it is stored, or a school can
 * link to — or, on an edit, take over — another school's row.
 */
public final class TenantRefs {

    private TenantRefs() {
    }

    /** True when nothing is linked ({@code null}) or the id resolves inside the caller's tenant. */
    public static boolean ownedOrAbsent(Long id, Function<Long, ? extends Optional<?>> scopedFinder) {
        return id == null || scopedFinder.apply(id).isPresent();
    }
}
