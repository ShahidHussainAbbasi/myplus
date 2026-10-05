package com.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** SESS-2 — a script on a dead session gets 401 SESSION_EXPIRED; a page still gets the Session expired page. */
class XhrAwareInvalidSessionStrategyTest {

    private final XhrAwareInvalidSessionStrategy strategy = new XhrAwareInvalidSessionStrategy("/invalidSession.html");

    @Test
    @DisplayName("jQuery's request gets 401 {code:SESSION_EXPIRED} and a fresh session, not a redirect to HTML")
    void scriptGetsTheSess1Answer() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/getUserSell");
        req.addHeader("X-Requested-With", "XMLHttpRequest");
        MockHttpServletResponse res = new MockHttpServletResponse();

        strategy.onInvalidSessionDetected(req, res);

        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("\"code\":\"SESSION_EXPIRED\""), res.getContentAsString());
        assertNotNull(req.getSession(false), "a new session, so /login is not itself answered as an invalid session");
    }

    @Test
    @DisplayName("a browser navigation is unchanged: 302 to the Session expired page")
    void pageStillRedirects() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/businessDashboard");
        req.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        MockHttpServletResponse res = new MockHttpServletResponse();

        strategy.onInvalidSessionDetected(req, res);

        assertEquals(302, res.getStatus());
        assertTrue(res.getRedirectedUrl().endsWith("/invalidSession.html"), res.getRedirectedUrl());
    }

    @Test
    @DisplayName("a fetch asking for JSON only is a script; anything listing text/html is a page")
    void classification() {
        MockHttpServletRequest json = new MockHttpServletRequest();
        json.addHeader("Accept", "application/json");
        assertTrue(XhrAwareInvalidSessionStrategy.isScript(json));

        MockHttpServletRequest both = new MockHttpServletRequest();
        both.addHeader("Accept", "text/html, application/json");
        assertEquals(false, XhrAwareInvalidSessionStrategy.isScript(both));

        assertEquals(false, XhrAwareInvalidSessionStrategy.isScript(new MockHttpServletRequest()));
    }
}
