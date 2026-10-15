package com.web.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * RW-0c — the monolith's endpoint rules (auth-service {@code endpoint_permission}, V26), most specific first, each with
 * its permission's LABEL from the catalogue. Loaded and kept by {@link AuthCatalog}.
 */
@Component
public class EndpointRuleCatalog implements PermissionInterceptor.RuleSource {

    static final String PATH = "/api/auth/org/endpoint-rules";

    private final AuthCatalog catalog;

    public EndpointRuleCatalog(AuthCatalog catalog) { this.catalog = catalog; }

    @Override
    public List<PermissionInterceptor.Rule> rules() {
        List<Map<String, Object>> rows = catalog.rows(PATH);
        if (rows == null) return null;                                       // not loaded yet
        List<PermissionInterceptor.Rule> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Object m = r.get("method"), p = r.get("path"), c = r.get("code");
            if (m == null || p == null || c == null) continue;
            Object label = r.get("label");
            out.add(new PermissionInterceptor.Rule(String.valueOf(m), String.valueOf(p), String.valueOf(c),
                    label == null ? String.valueOf(c) : String.valueOf(label)));
        }
        return out;
    }
}
