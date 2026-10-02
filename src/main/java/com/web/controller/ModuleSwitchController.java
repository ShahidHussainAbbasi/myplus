package com.web.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.AuthSettingsClient;
import com.web.util.ProxyErrors;

/**
 * EX-2a — the "Modules" card: switch an opt-in module (Expense management, and any later one) on or off from
 * ANY dashboard. Before this only the business Configuration screen could reach a capability switch, so a school
 * or a farm could never turn a module on.
 *
 * <h3>Which keys are modules — derived, never listed here</h3>
 * auth's catalog publishes every capability with its own default, and since EX-0a that default IS
 * {@code Capability.defaultOn()}. So an opt-in module is exactly an {@code org.cap.*} entry whose default is
 * {@code false}. A second list in the monolith would drift from the enum the first time a module is added.
 *
 * <h3>Allow-list on write</h3>
 * The save refuses any key outside that set, so this card can never flip a trade capability (installments,
 * expiry tracking…) — those stay on the business Configuration screen, where their consequences are explained.
 * Owner/admin is enforced by auth's own {@code @PreAuthorize}; a user-tier save fails there.
 */
@Controller
public class ModuleSwitchController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleSwitchController.class);

    @Autowired
    private AuthSettingsClient auth;

    @GetMapping(value = "/moduleSwitches", produces = "application/json")
    @ResponseBody
    public Map<String, Object> modules() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> e : optInModules()) {
                Map<String, Object> m = new LinkedHashMap<>();
                String key = String.valueOf(e.get("key"));
                m.put("key", key);
                m.put("code", key.substring("org.cap.".length()));
                m.put("label", e.get("label"));
                m.put("help", e.get("help"));
                m.put("enabled", "true".equalsIgnoreCase(String.valueOf(e.get("value"))));
                m.put("locked", Boolean.TRUE.equals(e.get("locked")));
                out.add(m);
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", true);
            res.put("data", out);
            return res;
        } catch (Exception e) {
            LOGGER.error("moduleSwitches proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    @PostMapping(value = "/saveModuleSwitch", produces = "application/json")
    @ResponseBody
    public Map<String, Object> save(@RequestParam("key") String key, @RequestParam("enabled") String enabled) {
        try {
            if (!isModule(key)) return refusal(key);
            return auth.save(key, String.valueOf("true".equalsIgnoreCase(enabled)));
        } catch (Exception e) {
            LOGGER.error("saveModuleSwitch proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    /** Back to the default (OFF for a module): REMOVES the override rather than saving "false". */
    @PostMapping(value = "/resetModuleSwitch", produces = "application/json")
    @ResponseBody
    public Map<String, Object> reset(@RequestParam("key") String key) {
        try {
            if (!isModule(key)) return refusal(key);
            return auth.reset(key);
        } catch (Exception e) {
            LOGGER.error("resetModuleSwitch proxy error", e);
            return ProxyErrors.failure(e);
        }
    }

    private boolean isModule(String key) {
        if (key == null || !key.startsWith("org.cap.")) return false;
        for (Map<String, Object> e : optInModules()) {
            if (key.equals(e.get("key"))) return true;
        }
        return false;
    }

    /** auth's catalog entries that are opt-in modules: org.cap.* with a catalog default of false. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> optInModules() {
        Map<String, Object> catalog = auth.catalog();
        Object data = catalog == null ? null : catalog.get("data");
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(data instanceof List)) return out;
        for (Object o : (List<Object>) data) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> e = (Map<String, Object>) o;
            Object key = e.get("key");
            if (key != null && String.valueOf(key).startsWith("org.cap.")
                    && "false".equalsIgnoreCase(String.valueOf(e.get("defaultValue")))) {
                out.add(e);
            }
        }
        return out;
    }

    private static Map<String, Object> refusal(String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("message", "\"" + key + "\" is not a module that can be switched here. Business features are set "
                + "on the business Configuration screen.");
        return m;
    }
}
