-- Slice 105 — notification-service finally gets a datastore.
-- Design: microservices/docs/slices/105-notification-multichannel-broadcast.md (G3, D3)
--
-- ── Why this table exists ────────────────────────────────────────────────────────────────────────
-- This service has been a stateless SMTP relay since it was built: no entity, no repository, no Flyway,
-- no datasource. A send either worked or logged a line. That is gap G3, and it is the reason two other
-- slices had to build their own durability first — education's `notify_outbox` (N1) and again for
-- notices (3.5) — because the shared service could not be trusted to not lose a message.
--
-- ── PER-RECIPIENT rows are the whole point ───────────────────────────────────────────────────────
-- "sent to 298, failed 2, HERE ARE THE 2" is an answer a school can act on. "failed 2" is not, and that
-- is all the platform could say until now. A parent ringing to ask why they never got the closure notice
-- is the case this table has to answer.
--
-- ── MySQL, not Redis (decided 2026-08-09) ────────────────────────────────────────────────────────
-- Redis is already deployed here and is the right tool for a dispatch QUEUE. It is the wrong tool for
-- this: a delivery record is evidence — durable, queryable by recipient and date, retained for months,
-- potentially audited. Redis is in-memory first with best-effort persistence and no ad-hoc queries, so
-- an eviction or an unclean shutdown would erase exactly what the slice exists to provide. If throughput
-- ever makes the dispatcher the bottleneck, Redis goes IN FRONT as the work queue and this stays the
-- record.
--
-- ── Retry lives here, and does NOT duplicate education's outbox ──────────────────────────────────
-- Each hop retries its own failure, which is correct layering rather than duplication:
--   education's notify_outbox : the send REQUEST survives an education restart (atomic with the notice)
--   this table               : the SMTP DELIVERY is retried, and its outcome is known per recipient
--
-- Idempotent so a re-run, or a dev DB ddl-auto already touched, is a no-op.

CREATE TABLE IF NOT EXISTS notification_broadcast (
    broadcast_id    BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id BIGINT       NULL,
    -- Which module asked, in its own words ("EDU-NOTICE", "ALERT", "COVER"). Opaque here: this service
    -- delivers, the caller knows why — the same boundary decision D-9 forced on the scheduling core.
    source          VARCHAR(64)  NULL,
    subject         VARCHAR(255) NULL,
    body            VARCHAR(4000) NULL,
    channel         ENUM('EMAIL','SMS') NOT NULL DEFAULT 'EMAIL',
    -- Caller-supplied idempotency key. A relay that re-POSTs after a timeout must not send twice, and
    -- the retry loops on BOTH sides make that a certainty rather than a risk.
    dedupe_key      VARCHAR(120) NULL,
    total_recipients INT         NOT NULL DEFAULT 0,
    created_at      DATETIME     NULL,
    PRIMARY KEY (broadcast_id),
    UNIQUE KEY uk_broadcast_dedupe (dedupe_key),
    KEY idx_broadcast_org (organization_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS notification_delivery (
    delivery_id     BIGINT       NOT NULL AUTO_INCREMENT,
    broadcast_id    BIGINT       NOT NULL,
    organization_id BIGINT       NULL,
    recipient       VARCHAR(255) NOT NULL,
    channel         ENUM('EMAIL','SMS') NOT NULL DEFAULT 'EMAIL',
    status          ENUM('PENDING','SENT','FAILED') NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000) NULL,
    sent_at         DATETIME     NULL,
    created_at      DATETIME     NULL,
    updated_at      DATETIME     NULL,
    PRIMARY KEY (delivery_id),
    -- THE dispatcher query: "what still needs sending", oldest first. Every retry pass runs it.
    KEY idx_delivery_pending (status, delivery_id),
    -- The support question: "what did this person get, and when".
    KEY idx_delivery_recipient (organization_id, recipient, created_at),
    KEY idx_delivery_broadcast (broadcast_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
