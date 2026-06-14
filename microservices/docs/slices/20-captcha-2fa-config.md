# Slice 20 — Configurable captcha (login/registration/forgot) + configurable 2FA

**Status: DESIGN → implementing.** Reuse the monolith's existing Google reCAPTCHA v2 integration; add it
to more forms; make **both captcha and 2FA toggleable** by config.

## Decisions
- Provider: **reuse Google reCAPTCHA v2** (`CaptchaService`/`CaptchaSettings`, site+`RECAPTCHA_SECRET`).
- Captcha on: **Login, Registration, Forgot-password**.
- **Configurable** (monolith properties, env-overridable):
  - `app.captcha.enabled` (default `false`) — render the widget + verify only when on.
  - `app.twofa.enabled` (default `true`) — show/allow the 2FA flow (login code field + console controls).

## Design
- **Config → UI:** a `WebFeatureAdvice` (`@ControllerAdvice @ModelAttribute`) exposes `captchaEnabled`,
  `captchaSiteKey`, `twoFaEnabled` to every server-rendered view (like `demoUser`).
- **Verify is a no-op when off:** `CaptchaService.processResponse` returns early when `app.captcha.enabled`
  is false, so callers can call it unconditionally.
- **Registration / Forgot (plain controller POSTs):** templates render the widget under
  `th:if="${captchaEnabled}"`; `RegistrationController.registerUserAccount` / `resetPassword` call
  `captchaService.processResponse(request.getParameter("g-recaptcha-response"))`.
- **Login (Spring Security):** `login.html` renders the widget (`th:if=captchaEnabled`) and gates the 2FA
  code field (`th:if=twoFaEnabled`); `CustomWebAuthenticationDetails` carries the `g-recaptcha-response`;
  `AuthServerAuthenticationProvider` verifies it (when enabled) before delegating to auth-service —
  failure → `BadCredentialsException`. Wired via the provider `@Bean` in `SecSecurityConfig`.
- **2FA UI:** `console.html` 2FA enable/disable controls under `th:if="${twoFaEnabled}"`.

## Test
- captcha off (default): forms work with no widget; verify is skipped.
- captcha on: widget shows; a bad/missing token blocks login/registration/forgot; a valid token passes.
- 2FA off: login code field + console 2FA controls hidden.
- Cypress: login/registration still pass with captcha off; widget present when on (stub verify).
