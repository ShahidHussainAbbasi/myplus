/*
 * The ONE way to read a server response.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * THE PROBLEM THIS SOLVES
 *
 * The platform answers in two envelopes, for historical reasons:
 *
 *     ApiResponse       { success: true,        message, data,       statusCode }
 *     GenericResponse   { status: "SUCCESS",    message, object, collection }
 *
 * so every caller had to know which endpoint it was talking to. Across the front end that produced 44 places
 * reading `.success`, 64 reading `status === 'SUCCESS'`, and 14 error branches that threw the server's
 * message away and showed a hard-coded sentence instead — so the user was told "Could not save the product"
 * when the server had said exactly which SKU was duplicated, or that their trial had ended.
 *
 * Reading the wrong field never throws. It silently answers "not successful" for a call that worked, or
 * "fine" for one that failed. That is the worst possible failure mode: no error, no log, wrong screen.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────────────────
 * THE STANDARD
 *
 *   1. Never read `resp.success` or `resp.status` directly. Call `apiOk(resp)`.
 *   2. Never display a hard-coded failure sentence. Call `apiMessage(resp, fallback)` — the SERVER'S message
 *      wins, and the fallback is used only when it did not send one.
 *   3. Read payloads with `apiData(resp)` / `apiList(resp)`, never `resp.data` or `resp.collection` directly.
 *
 * A message the server took the trouble to write is the only text on the screen that knows what actually
 * happened. The fallback exists for when there is nothing better, not as the default.
 *
 * Server side, `GenericResponse` now also exposes a derived `success`, so both envelopes answer both
 * questions and these helpers cannot be wrong about either.
 */
(function (global) {
    'use strict';

    /** Did the call succeed? Understands both envelopes, and treats an absent/garbled body as failure. */
    function apiOk(resp) {
        if (!resp) return false;
        if (typeof resp.success === 'boolean') return resp.success;
        if (typeof resp.status === 'string') {
            var s = resp.status.toUpperCase();
            return s === 'SUCCESS' || s === 'OK';
        }
        return false;
    }

    /**
     * What to tell the user. THE SERVER'S SENTENCE WINS.
     *
     * `fallback` is for when the server said nothing — a network drop, a 500 with an empty body. It is not a
     * house style to be preferred over what actually happened.
     */
    function apiMessage(resp, fallback) {
        if (resp) {
            if (resp.message && String(resp.message).trim()) return String(resp.message);
            if (resp.error && String(resp.error).trim()) return String(resp.error);
        }
        return fallback || 'Something went wrong. Please try again.';
    }

    /** The single payload: ApiResponse `data`, GenericResponse `object`. */
    function apiData(resp) {
        if (!resp) return null;
        return (resp.data !== undefined && resp.data !== null) ? resp.data : resp.object;
    }

    /**
     * The list payload: GenericResponse `collection`, ApiResponse `data`.
     *
     * ⚠ Always an ARRAY. Returning undefined for "no rows" is how a `.length` read becomes a crash and a
     * `.filter` becomes a silent empty screen.
     */
    function apiList(resp) {
        if (!resp) return [];
        if (Array.isArray(resp.collection)) return resp.collection;
        if (Array.isArray(resp.data)) return resp.data;
        if (resp.data && Array.isArray(resp.data.content)) return resp.data.content;   // Spring Page
        return [];
    }

    /**
     * What to tell the user when jQuery took the FAILURE path.
     *
     * ⚠ THIS IS WHERE SERVER MESSAGES GO TO DIE. jQuery routes any non-2xx to `error`/`fail`, and the
     * handler receives the jqXHR — not the body — so the natural thing to write is a generic sentence.
     * Across this codebase that produced handlers telling the user <i>"Network error. Please check your
     * connection"</i> when the connection was fine and the server had said, in the very response being
     * ignored, that their free trial had ended.
     *
     * The body is right there in `responseJSON`. A 403 with a sentence is an ANSWER, not a network failure.
     *
     * @param jqXHR    the object jQuery hands `error`/`fail`
     * @param fallback used only for a genuine transport failure, where status is 0 and there is no body
     */
    function apiFailMessage(jqXHR, fallback) {
        var body = jqXHR && (jqXHR.responseJSON || parseBody(jqXHR.responseText));
        if (body) {
            var m = apiMessage(body, null);
            if (m && m !== 'Something went wrong. Please try again.') return m;
        }
        var status = jqXHR && jqXHR.status;
        if (status === 403) return 'You do not have permission to do that.';
        if (status === 404) return 'That action is not available on the server.';
        if (status === 0) return fallback || 'Network error. Please check your connection and try again.';
        return fallback || 'Something went wrong. Please try again.';
    }

    function parseBody(text) {
        if (!text || typeof text !== 'string') return null;
        try { return JSON.parse(text); } catch (e) { return null; }
    }

    /**
     * The whole pattern in one call, for the common case.
     *
     *   apiHandle(resp, { ok: function (data) { ... }, fallback: 'Could not save the product.' })
     *
     * Success runs `ok`; failure shows the server's reason through the shared error display. Nothing is
     * swallowed either way.
     */
    function apiHandle(resp, opts) {
        opts = opts || {};
        if (apiOk(resp)) {
            if (typeof opts.ok === 'function') opts.ok(apiData(resp), resp);
            if (opts.successMessage) showApiMessage(apiMessage(resp, opts.successMessage), true);
            return true;
        }
        showApiMessage(apiMessage(resp, opts.fallback), false);
        return false;
    }

    /**
     * Display, routed to whichever shared display this page already has.
     *
     * Deliberately does NOT invent a new toast: modules already load `showFormError` / `showSaleError` /
     * `uiAlert`, and a fourth mechanism would be the DRY violation this file exists to remove.
     */
    function showApiMessage(text, ok) {
        if (ok && typeof global.showSaleSuccess === 'function') return global.showSaleSuccess(text);
        if (!ok && typeof global.showFormError === 'function') return global.showFormError(text);
        if (!ok && typeof global.showSaleError === 'function') return global.showSaleError(text);
        if (typeof global.uiAlert === 'function') return global.uiAlert(text);
        console[ok ? 'log' : 'error'](text);
    }

    global.apiOk = apiOk;
    global.apiFailMessage = apiFailMessage;
    global.apiMessage = apiMessage;
    global.apiData = apiData;
    global.apiList = apiList;
    global.apiHandle = apiHandle;
    global.showApiMessage = showApiMessage;
})(window);
