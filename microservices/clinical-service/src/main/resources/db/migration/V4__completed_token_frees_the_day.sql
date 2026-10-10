-- HMS S3a — a COMPLETED visit no longer blocks a new token with the same doctor the same day.
--
-- V2's live_patient_id treated COMPLETED as live, while QueueService's own rule ("one waiting-or-ongoing token per
-- patient per doctor per day", QueueStatus.LIVE) does not. The two disagreed: a patient who came back to the same
-- doctor later the same day passed the service check and then hit uq_token_live — a raw 500 (S3a gate D-03,
-- "Duplicate entry ... for key queue_token.uq_token_live"). The service's rule is the intended one (02b is about
-- duplicate WAITING tokens), so the column now matches it: NULL once the token is over (COMPLETED, CANCELLED, NO_SHOW).
--
-- MODIFY on a STORED generated column recomputes every row and keeps uq_token_live. Existing completed rows become
-- NULL, which only ever frees places; nothing that is live today changes. Re-running is a no-op (same definition).
ALTER TABLE queue_token
    MODIFY COLUMN live_patient_id BIGINT
        AS (CASE WHEN status IN ('CANCELLED', 'NO_SHOW', 'COMPLETED') THEN NULL ELSE patient_id END) STORED;
