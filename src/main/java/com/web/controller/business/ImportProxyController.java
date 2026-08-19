package com.web.controller.business;

import java.util.Collections;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.web.util.BusinessRestClient;

/**
 * Slice I1 — browser-facing proxies for the CSV template/import endpoints on business-service.
 *
 * <p>Shipped WITH the slice rather than after it. An endpoint with no proxy is unreachable from the only UI
 * this platform has, which is review finding R7 — hit three times in the OMS programme, each time by a
 * capability that was built, tested and then reachable by nobody.
 *
 * <p>These proxies add nothing: no authorisation decision, no reshaping, no defaulting. The privilege check
 * lives on business-service ({@code ADMIN_PRIVILEGE}), so a non-admin who reaches this method still gets the
 * refusal from the service — a proxy that quietly allowed what the service forbids would be the worst of both.
 */
@Controller
public class ImportProxyController {

    private final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @Autowired
    private BusinessRestClient client;

    /** Which entities can be imported — the browser draws its grid buttons from this. */
    @GetMapping("/import/entities")
    @ResponseBody
    public Map<String, Object> entities() {
        try {
            return client.get("/import/entities");
        } catch (Exception e) {
            LOGGER.error("import entities proxy error", e);
            // An empty list, not an error shape: the caller's only use is "should I draw the buttons?", and
            // the honest answer when the service is unreachable is "no".
            return Map.of("status", "SUCCESS", "collection", Collections.emptyList());
        }
    }

    /** The blank template, streamed straight through as a download. */
    @GetMapping("/import/{entity}/template.csv")
    @ResponseBody
    public ResponseEntity<String> template(@PathVariable("entity") String entity) {
        try {
            String csv = client.getString("/import/" + entity + "/template.csv");
            return ResponseEntity.ok()
                    .header("Content-Disposition",
                            "attachment; filename=\"" + entity + "-import-template.csv\"")
                    .header("Content-Type", "text/csv; charset=UTF-8")
                    .body(csv);
        } catch (Exception e) {
            LOGGER.error("import template proxy error", e);
            return ResponseEntity.status(502).body("Could not build the template. Please try again.");
        }
    }

    /** Dry run — writes nothing, returns the row-by-row report. */
    @PostMapping("/import/{entity}/validate")
    @ResponseBody
    public Map<String, Object> validate(@PathVariable("entity") String entity,
                                        @RequestBody Map<String, Object> body) {
        try {
            return client.postJson("/import/" + entity + "/validate", body);
        } catch (Exception e) {
            LOGGER.error("import validate proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /** Commit — writes only if every row is acceptable. */
    @PostMapping("/import/{entity}/commit")
    @ResponseBody
    public Map<String, Object> commit(@PathVariable("entity") String entity,
                                      @RequestBody Map<String, Object> body) {
        try {
            return client.postJson("/import/" + entity + "/commit", body);
        } catch (Exception e) {
            LOGGER.error("import commit proxy error", e);
            return Collections.singletonMap("status", "ERROR");
        }
    }

    /**
     * The report as a downloadable CSV — for a file too long to read in a modal.
     *
     * <p>Takes the CSV as a FORM field, not a JSON body, and that is deliberate: the response is a download,
     * so the browser has to submit a real form for the filename to survive — an XHR would hand the text back
     * to JavaScript with nowhere to put it. The proxy translates form → JSON for the service, so the service
     * keeps one body shape.
     *
     * <p>Note what is NOT done here: mixing {@code @RequestParam} with {@code @RequestBody} on one method.
     * That combination silently breaks a Spring HTTP-interface client (it encodes params as form data on a
     * POST, which cannot coexist with a body) and cost O7 D4 an afternoon.
     */
    @PostMapping(value = "/import/{entity}/report.csv", consumes = "application/x-www-form-urlencoded")
    @ResponseBody
    public ResponseEntity<String> report(@PathVariable("entity") String entity,
                                         @RequestParam("csv") String csvText) {
        try {
            String csv = client.postJsonString("/import/" + entity + "/report.csv",
                    Collections.singletonMap("csv", csvText));
            return ResponseEntity.ok()
                    .header("Content-Disposition",
                            "attachment; filename=\"" + entity + "-import-report.csv\"")
                    .header("Content-Type", "text/csv; charset=UTF-8")
                    .body(csv);
        } catch (Exception e) {
            LOGGER.error("import report proxy error", e);
            return ResponseEntity.status(502).body("Could not build the report.");
        }
    }
}
