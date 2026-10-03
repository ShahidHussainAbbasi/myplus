package com.web.controller.ecommerce;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MKT-1c/1d/1e — the ANONYMOUS marketplace: the {@code /marketplace} page, its reads, and the one anonymous write
 * (checkout). {@code SecSecurityConfig} permits GET {@code /marketplace}, GET {@code /marketplace/public/**} and POST
 * {@code /marketplace/public/checkout} without a login; CSRF stays enforced on the POST.
 *
 * <p>Calls the gateway's allow-listed {@code /api/marketplace/public/**} with no token. {@link RestClient} (Spring
 * 6.1), the successor to {@code RestTemplate}, with connect and read TIMEOUTS (standard D3e): an anonymous page that
 * pins a request thread on a slow service is the cheapest way to take the monolith down. Query strings are built by
 * {@link UriComponentsBuilder} (each value encoded), never by concatenation.
 */
@Controller
public class MarketplacePublicController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarketplacePublicController.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() { };

    private final String gatewayUrl;
    private final RestClient http;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    public MarketplacePublicController(@Value("${gateway.url:http://localhost:8765}") String gatewayUrl) {
        this(gatewayUrl, RestClient.builder().requestFactory(timeouts()).build());
    }

    /** Tests bind a mock server to the client. */
    MarketplacePublicController(String gatewayUrl, RestClient http) {
        this.gatewayUrl = gatewayUrl;
        this.http = http;
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(3));
        timeouts.setReadTimeout(Duration.ofSeconds(5));
        return timeouts;
    }

    /** The public marketplace page (MKT-1d). State lives in the URL: ?q=&city=&product=&sort=. */
    @GetMapping("/marketplace")
    public String page() {
        return "marketplace";
    }

    /** Search cards: "Available from N sellers · From Rs. …" (MKT-1d). */
    @GetMapping("/marketplace/public/products")
    @ResponseBody
    public Object search(@RequestParam(required = false) String q, @RequestParam(required = false) String city,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return relay("/api/marketplace/public/mkt/products", Map.of(),
                query("q", blank(q), "city", blank(city), "page", page, "size", size), "Could not search the marketplace.");
    }

    /** A product page header with the default sort and the sorts on offer (MKT-1d). */
    @GetMapping("/marketplace/public/products/{id}")
    @ResponseBody
    public Object product(@PathVariable Long id) {
        return relay("/api/marketplace/public/mkt/products/{id}", Map.of("id", id), Map.of(), "Could not load the product.");
    }

    /** A product's offers, ranked by the customer's sort and filtered by city and quantity (MKT-1c). */
    @GetMapping("/marketplace/public/products/{id}/offers")
    @ResponseBody
    public Object offers(@PathVariable Long id, @RequestParam(required = false) String city,
            @RequestParam(required = false) String sort, @RequestParam(required = false) BigDecimal qty) {
        return relay("/api/marketplace/public/mkt/products/{id}/offers", Map.of("id", id),
                query("city", blank(city), "sort", blank(sort), "qty", qty == null ? null : qty.toPlainString()), "Could not load offers.");
    }

    /**
     * MKT-1e checkout — the one anonymous WRITE. Permitted by method and path in {@code SecSecurityConfig}; CSRF is
     * NOT exempted (the page sends the token). The body is passed through: the service binds only the fields it
     * knows and prices everything itself.
     */
    @PostMapping("/marketplace/public/checkout")
    @ResponseBody
    public Object checkout(@RequestBody Map<String, Object> body, jakarta.servlet.http.HttpServletRequest request) {
        // MKT-1e2: a signed-in customer checks out on the account route (the order is theirs; card payment allowed)
        String session = sessionOf(request);
        URI uri = UriComponentsBuilder.fromUriString(gatewayUrl)
                .path(session == null ? "/api/marketplace/public/mkt/checkout" : "/api/marketplace/public/mkt/account/checkout")
                .build().toUri();
        try {
            Map<String, Object> r = http.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> { if (session != null) h.set(SESSION_HEADER, session); })
                    .body(body).retrieve().body(JSON);
            return r == null ? Map.of("success", false, "message", "Could not place the order.") : r;
        } catch (HttpStatusCodeException e) {
            LOGGER.warn("marketplace checkout -> {}", e.getStatusCode());
            // A 4xx is the service's answer ("no"). A 5xx from the gateway (502/504) is NOT: the order may exist.
            return e.getStatusCode().is5xxServerError() ? unknownOutcome()
                    : Map.of("success", false, "statusCode", e.getStatusCode().value(),
                            "message", messageOf(e, "Could not place the order. Please try again."));
        } catch (Exception e) {
            LOGGER.error("marketplace checkout failed", e);
            return unknownOutcome();
        }
    }

    /**
     * A timeout may hide an order that WAS placed. The page must keep its attempt key so that pressing again returns
     * that order instead of placing a second one — so this says UNKNOWN in a field the page acts on. As a plain
     * {success:false} it read like a refusal: the page dropped the key and a retry placed a DUPLICATE order (found
     * tracing the wire for the recorded manual walk, M-1e-05).
     */
    static Map<String, Object> unknownOutcome() {
        return Map.of("success", false, "outcome", "UNKNOWN",
                "message", "We could not confirm your order. Press the button again; it will not be placed twice.");
    }

    // ── MKT-1e2: the marketplace customer's account ────────────────────────────────────────────────────

    /** The browser's cookie: HttpOnly (page script never reads it), Lax, scoped to /marketplace. */
    static final String SESSION_COOKIE = "MKT_SESSION";
    static final String SESSION_HEADER = "X-Mkt-Session";
    private static final java.time.Duration SESSION_AGE = java.time.Duration.ofDays(30);

    @PostMapping("/marketplace/account/register")
    @ResponseBody
    public Object register(@RequestBody Map<String, Object> body, jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        return signIn(accountPost("register", body, null), request, response);
    }

    @PostMapping("/marketplace/account/login")
    @ResponseBody
    public Object login(@RequestBody Map<String, Object> body, jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        return signIn(accountPost("login", body, null), request, response);
    }

    @PostMapping("/marketplace/account/logout")
    @ResponseBody
    public Object logout(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        Map<String, Object> r = accountPost("logout", Map.of(), sessionOf(request));
        setCookie(response, request, "", java.time.Duration.ZERO);               // gone from the browser either way
        return r;
    }

    @GetMapping("/marketplace/account/me")
    @ResponseBody
    public Object me(jakarta.servlet.http.HttpServletRequest request) {
        return accountGet("/api/marketplace/public/mkt/account/me", Map.of(), sessionOf(request));
    }

    @GetMapping("/marketplace/account/orders")
    @ResponseBody
    public Object myOrders(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            jakarta.servlet.http.HttpServletRequest request) {
        return accountGet("/api/marketplace/public/mkt/account/orders", query("page", page, "size", size), sessionOf(request));
    }

    @PostMapping("/marketplace/account/claim")
    @ResponseBody
    public Object claim(@RequestBody Map<String, Object> body, jakarta.servlet.http.HttpServletRequest request) {
        return accountPost("claim", body, sessionOf(request));
    }

    @PostMapping("/marketplace/account/orders/{orderNo}/cancel")
    @ResponseBody
    public Object cancel(@PathVariable String orderNo, @RequestBody(required = false) Map<String, Object> body,
            jakarta.servlet.http.HttpServletRequest request) {
        URI uri = UriComponentsBuilder.fromUriString(gatewayUrl).path("/api/marketplace/public/mkt/account/orders/{no}/cancel")
                .encode().buildAndExpand(Map.of("no", orderNo)).toUri();
        return accountCall(uri, body == null ? Map.of() : body, sessionOf(request));
    }

    // ── MKT-1f: support cases (the customer talks to MaxTheService only) ──────────────────────────────

    @PostMapping("/marketplace/account/orders/{orderNo}/cases")
    @ResponseBody
    public Object openCase(@PathVariable String orderNo, @RequestBody(required = false) Map<String, Object> body,
            jakarta.servlet.http.HttpServletRequest request) {
        return accountCall(uri(gatewayUrl, "/api/marketplace/public/mkt/account/orders/{no}/cases", Map.of("no", orderNo), Map.of()),
                body == null ? Map.of() : body, sessionOf(request));
    }

    @GetMapping("/marketplace/account/cases")
    @ResponseBody
    public Object myCases(jakarta.servlet.http.HttpServletRequest request) {
        return accountGet("/api/marketplace/public/mkt/account/cases", Map.of(), sessionOf(request));
    }

    @GetMapping("/marketplace/account/cases/{caseNo}")
    @ResponseBody
    public Object myCase(@PathVariable String caseNo, jakarta.servlet.http.HttpServletRequest request) {
        return accountGetVars("/api/marketplace/public/mkt/account/cases/{no}", Map.of("no", caseNo), sessionOf(request));
    }

    @PostMapping("/marketplace/account/cases/{caseNo}/messages")
    @ResponseBody
    public Object caseMessage(@PathVariable String caseNo, @RequestBody(required = false) Map<String, Object> body,
            jakarta.servlet.http.HttpServletRequest request) {
        return accountCall(uri(gatewayUrl, "/api/marketplace/public/mkt/account/cases/{no}/messages", Map.of("no", caseNo), Map.of()),
                body == null ? Map.of() : body, sessionOf(request));
    }

    /** The token goes into the HttpOnly cookie and is REMOVED from what the page receives. */
    @SuppressWarnings("unchecked")
    private Object signIn(Map<String, Object> r, jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response) {
        if (!Boolean.TRUE.equals(r.get("success")) || !(r.get("data") instanceof Map<?, ?> d) || d.get("token") == null) return r;
        setCookie(response, request, String.valueOf(d.get("token")), SESSION_AGE);
        Map<String, Object> out = new java.util.LinkedHashMap<>(r);
        out.put("data", Map.of("name", String.valueOf(d.get("name")), "phone", String.valueOf(d.get("phone"))));
        return out;
    }

    private static void setCookie(jakarta.servlet.http.HttpServletResponse response, jakarta.servlet.http.HttpServletRequest request,
            String value, java.time.Duration age) {
        response.addHeader(org.springframework.http.HttpHeaders.SET_COOKIE,
                org.springframework.http.ResponseCookie.from(SESSION_COOKIE, value).httpOnly(true).secure(request.isSecure())
                        .sameSite("Lax").path("/marketplace").maxAge(age).build().toString());
    }

    static String sessionOf(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null || request.getCookies() == null) return null;
        for (jakarta.servlet.http.Cookie c : request.getCookies())
            if (SESSION_COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) return c.getValue();
        return null;
    }

    private Map<String, Object> accountPost(String action, Object body, String session) {
        return accountCall(UriComponentsBuilder.fromUriString(gatewayUrl).path("/api/marketplace/public/mkt/account/" + action)
                .build().toUri(), body, session);
    }

    private Map<String, Object> accountCall(URI uri, Object body, String session) {
        try {
            Map<String, Object> r = http.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> { if (session != null) h.set(SESSION_HEADER, session); })
                    .body(body).retrieve().body(JSON);
            return r == null ? Map.of("success", false, "message", "Please try again.") : r;
        } catch (HttpStatusCodeException e) {
            LOGGER.warn("marketplace account {} -> {}", uri.getPath(), e.getStatusCode());
            return Map.of("success", false, "statusCode", e.getStatusCode().value(), "message",
                    e.getStatusCode().is5xxServerError() ? "The marketplace is not available right now. Please try again."
                            : messageOf(e, "Please try again."));
        } catch (Exception e) {
            LOGGER.error("marketplace account {} failed", uri.getPath(), e);
            return Map.of("success", false, "message", "The marketplace is not available right now. Please try again.");
        }
    }

    private Map<String, Object> accountGet(String path, Map<String, Object> query, String session) {
        return accountRead(path, Map.of(), query, session);
    }

    /** A read whose path carries a value (strictly encoded as a URI variable, never concatenated). */
    private Map<String, Object> accountGetVars(String path, Map<String, Object> vars, String session) {
        return accountRead(path, vars, Map.of(), session);
    }

    private Map<String, Object> accountRead(String path, Map<String, Object> vars, Map<String, Object> query, String session) {
        URI uri = null;
        try {
            uri = uri(gatewayUrl, path, vars, query);
            Map<String, Object> r = http.get().uri(uri)
                    .headers(h -> { if (session != null) h.set(SESSION_HEADER, session); })
                    .retrieve().body(JSON);
            return r == null ? Map.of("success", false, "message", "Please try again.") : r;
        } catch (HttpStatusCodeException e) {
            return Map.of("success", false, "statusCode", e.getStatusCode().value(), "message",
                    e.getStatusCode().is5xxServerError() ? "The marketplace is not available right now. Please try again."
                            : messageOf(e, "Please try again."));
        } catch (Exception e) {
            LOGGER.error("marketplace account read {} failed", uri == null ? path : uri.getPath(), e);
            return Map.of("success", false, "message", "The marketplace is not available right now. Please try again.");
        }
    }

    /** MKT-1e tracking: the order number AND the phone it was placed with. */
    @GetMapping("/marketplace/public/orders/{orderNo}")
    @ResponseBody
    public Object track(@PathVariable String orderNo, @RequestParam(required = false) String phone) {
        return relay("/api/marketplace/public/mkt/orders/{no}", Map.of("no", orderNo), query("phone", blank(phone)),
                "Could not load the order.");
    }

    /**
     * One relay for every read: the service's own sentence reaches the shopper (a 404 "No such product." stays
     * that sentence); an outage reads as a polite retry, never a stack trace.
     */
    private Object relay(String path, Map<String, ?> pathVars, Map<String, Object> query, String fallback) {
        URI uri = null;
        try {
            uri = uri(gatewayUrl, path, pathVars, query);
            Map<String, Object> r = http.get().uri(uri).retrieve().body(JSON);
            return r == null ? Map.of("success", false, "message", fallback) : r;
        } catch (HttpStatusCodeException e) {
            LOGGER.warn("marketplace public read {} -> {}", uri.getPath(), e.getStatusCode());
            return Map.of("success", false, "statusCode", e.getStatusCode().value(), "message", messageOf(e, fallback));
        } catch (Exception e) {
            LOGGER.error("marketplace public read {} failed", uri == null ? path : uri.getPath(), e);
            return Map.of("success", false, "message", "The marketplace is not available right now. Please try again.");
        }
    }

    @SuppressWarnings("unchecked")
    private String messageOf(HttpStatusCodeException e, String fallback) {
        try {
            Object m = objectMapper.readValue(e.getResponseBodyAsString(), Map.class).get("message");
            return m == null ? fallback : String.valueOf(m);
        } catch (Exception ignore) {
            return fallback;
        }
    }

    /**
     * The gateway URL with the shopper's text as DATA: every value goes in as a URI variable and is strictly encoded
     * AFTER the template is ({@code encode().buildAndExpand}). Building first and encoding the result read a search
     * for "%{enter}" as the template variable {enter} — an exception before the try, a raw 500 and "InternalError"
     * on the page (found by the recorded manual walk, M-1d-08). Strict encoding also keeps "+", "&" and "%" literal.
     */
    static URI uri(String base, String path, Map<String, ?> pathVars, Map<String, Object> query) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(base).path(path);
        Map<String, Object> vars = new java.util.HashMap<>(pathVars);
        query.forEach((k, v) -> {
            b.queryParam(k, "{" + k + "}");
            vars.put(k, v);
        });
        return b.encode().buildAndExpand(vars).toUri();
    }

    /** Name/value pairs in order; a null (or blank-empty) value is left out. */
    private static Map<String, Object> query(Object... kv) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1] instanceof java.util.Optional<?> o ? o.orElse(null) : kv[i + 1];
            if (v != null) out.put((String) kv[i], v);
        }
        return out;
    }

    private static java.util.Optional<String> blank(String s) {
        return s == null || s.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(s.trim());
    }
}
