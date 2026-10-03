package com.myplus.marketplace.multiseller.service;

import java.util.List;

/**
 * MKT-0a — the agreements a seller accepts before selling (source §9, §20 Phase 0).
 *
 * <p>The TEXT lives with the operator's policies (design §6.5); this class fixes the codes and the version the
 * service currently requires. Raising {@link #CURRENT_VERSION} makes every seller re-accept before their next
 * write: an acceptance of v1 never counts as agreeing to v2.
 */
public final class MarketplaceAgreements {

    public static final String SELLER = "SELLER";
    /** Source §9: data use is a written data-sharing agreement — never called "consignment". */
    public static final String DATA_SHARING = "DATA_SHARING";
    public static final List<String> REQUIRED = List.of(SELLER, DATA_SHARING);
    public static final String CURRENT_VERSION = "v1";

    private MarketplaceAgreements() {
    }
}
