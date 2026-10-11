-- H6 C4 (docs/hms-phase1-design.md §4k) — coupon windows stored as the UTC instant, like every other time.
--
-- Until now a window was stored AS TYPED (the shop's wall time, e.g. 10:30) in a LocalDateTime the JVM treats as
-- UTC; the JDBC connection then wrote it at +05:00, so the column holds 15:30. From this release the service converts
-- the typed time from the business's zone to UTC before saving (10:30 Karachi → 05:30 UTC → stored 10:30 here), and
-- compares it with the UTC clock. This moves every window typed before the release the same way: −5 h.
--
-- Scope, measured on DEV 2026-10-11: 6 coupons have a window — all in one tenant (the marketplace demo), all created by
-- the coupon gate that day. Every tenant uses the platform's business zone (app.tz.default-zone = Asia/Karachi; there
-- is no per-business zone yet), so the same −5 h is right for every row. Runs once (Flyway).

UPDATE coupon SET starts_at = starts_at - INTERVAL 5 HOUR WHERE starts_at IS NOT NULL;
UPDATE coupon SET ends_at   = ends_at   - INTERVAL 5 HOUR WHERE ends_at   IS NOT NULL;
