-- Period close / lock: one lock date per org. Everything dated on/before locked_through is frozen (read-only).
-- NULL / no row = nothing locked. finance-service is the single source of truth; business-service reads it to gate its ops.

CREATE TABLE period_lock (
    id               BIGINT   NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT   NULL,
    locked_through   DATE     NULL,       -- freeze everything on/before this date; null = open
    updated_by       BIGINT   NULL,
    updated_at       DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_period_lock_org UNIQUE (organization_id)
);
