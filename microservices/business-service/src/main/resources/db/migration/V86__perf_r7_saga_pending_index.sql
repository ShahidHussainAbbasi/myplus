-- PERF-R7 (2026-10-15) — the saga recovery poller stops scanning every tenant's sales every minute.
-- Review: microservices/docs/slices/perf-r1-review-2026-10-15.md (R7). Standard §1f P1-P2.
--
-- SagaRecoveryRelay runs CustomerHistoryRepo.findPendingSagaSales() every minute:
--     WHERE saga_status = 'PENDING' AND reservation_id IS NOT NULL ORDER BY dated
-- It is a background job, so it has no tenant filter — and customer_history had no index on saga_status. Measured on
-- the Docker DB: EXPLAIN type ALL, 3,788 rows + filesort to return 3; 520 calls in 15.7 h, 467 of them without an
-- index. It reads the WHOLE platform's sale headers once a minute and grows with every sale every tenant makes.
--
-- (saga_status, dated) answers the filter and the ORDER BY from the index: only the pending rows are read, already
-- in order. reservation_id IS NOT NULL is checked on those few rows.
-- ⚠ PREFIX saga_status(16): customer_history is MyISAM (1000-byte key limit) and saga_status is VARCHAR(255) utf8mb4
-- (1,020 bytes) — a full-column index FAILS to build, which would stop business-service at boot. Values are at most 9
-- characters (PENDING / CONFIRMED / FAILED), so 16 characters is exact. Proven on a TEMPORARY copy before writing.
-- Idempotent (D7). No placeholder syntax anywhere in this file (Flyway would substitute it, even in a comment).

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_history' AND INDEX_NAME = 'idx_ch_saga_pending') = 0,
    'CREATE INDEX idx_ch_saga_pending ON customer_history (saga_status(16), dated)',
    'DO 0');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
