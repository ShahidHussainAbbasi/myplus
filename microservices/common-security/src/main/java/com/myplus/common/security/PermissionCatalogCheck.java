package com.myplus.common.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * RW-0c (2026-10-12) — a service names the permission each of its ACTIONS needs (that much must live in code: the
 * action says what it requires — the Odoo pattern). Everything else about permissions is data in auth-service. This
 * check, run once at start-up, makes sure every code a service names really EXISTS in that data — a renamed or
 * deleted code would otherwise refuse everyone, silently, for as long as nobody noticed.
 *
 * <p>It never stops a service: a mismatch is an ERROR in the log naming each missing code; auth-service being
 * unreachable is a WARN (the check is not worth a failed start). Reads auth-service's public list of codes —
 * {@code /api/auth/catalog/permission-codes} — through the caller's load-balanced builder.
 */
public final class PermissionCatalogCheck {

    private static final Logger LOG = LoggerFactory.getLogger(PermissionCatalogCheck.class);
    static final String CATALOG = "http://auth-service/api/auth/catalog/permission-codes";

    private PermissionCatalogCheck() {}

    /** The codes in {@code named} that the catalogue does not have. */
    public static List<String> missing(Collection<String> named, Collection<String> catalogue) {
        return named.stream().filter(c -> catalogue == null || !catalogue.contains(c)).sorted().toList();
    }

    @SuppressWarnings("unchecked")
    public static void verify(RestClient.Builder loadBalanced, String service, Collection<String> named) {
        try {
            Map<String, Object> body = loadBalanced.clone().build().get().uri(CATALOG).retrieve().body(Map.class);
            Object data = body == null ? null : body.get("data");
            if (!(data instanceof List<?> list)) {
                LOG.warn("RW-0c {}: permission catalogue answered without a list — not checked", service);
                return;
            }
            List<String> gone = missing(named, list.stream().map(String::valueOf).toList());
            if (gone.isEmpty()) {
                LOG.info("RW-0c {}: all {} permission codes it names exist in the catalogue", service, named.size());
            } else {
                gone.forEach(c -> LOG.error("RW-0c {}: names permission code '{}', which the catalogue does not have — "
                        + "every check on it refuses everyone. Add the code (auth migration) or fix the name.", service, c));
            }
        } catch (Exception e) {
            LOG.warn("RW-0c {}: permission catalogue unreachable ({}) — codes not checked this start", service, e.getMessage());
        }
    }
}
