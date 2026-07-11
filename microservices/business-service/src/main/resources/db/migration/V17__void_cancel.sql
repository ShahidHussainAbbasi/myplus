-- Audit #3: first-class Void/Cancel. Documents are soft-voided (record + audit survive), not hard-deleted, so a
-- cancellation reverses stock + AR/AP + GL and stamps who/when/why. NULL status == ACTIVE (legacy rows unaffected).

ALTER TABLE customer_history
    ADD COLUMN status       VARCHAR(16)   NULL,
    ADD COLUMN voided_by    BIGINT        NULL,
    ADD COLUMN voided_at    DATETIME      NULL,
    ADD COLUMN void_reason  VARCHAR(500)  NULL;

ALTER TABLE purchase
    ADD COLUMN status       VARCHAR(16)   NULL,
    ADD COLUMN voided_by    BIGINT        NULL,
    ADD COLUMN voided_at    DATETIME      NULL,
    ADD COLUMN void_reason  VARCHAR(500)  NULL;
