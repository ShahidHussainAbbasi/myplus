-- HMS S2 — where a provider (doctor) that broke tenancy is moved, instead of being deleted.
--
-- Until S2, DoctorService.create saved whatever venue id it was sent, so a doctor could be created in organisation A
-- pointing at organisation B's venue (found by hms-s2-token-queue.cy.js S2-11). The create path is fixed; the rows
-- that slipped in before the fix are moved here by ProviderIntegrityRepair, which runs at every start (idempotent).
--
-- Same columns as provider (CREATE … LIKE), plus when and why. Nothing is lost: a row can be put back by hand after
-- review. The repair NEVER moves a provider that has bookings or slots — those it only reports, because which
-- organisation they really belong to cannot be proven from the data.

CREATE TABLE IF NOT EXISTS provider_quarantine LIKE provider;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'provider_quarantine'
                  AND column_name = 'quarantined_at') = 0,
    'ALTER TABLE provider_quarantine ADD COLUMN quarantined_at DATETIME NULL, ADD COLUMN quarantine_reason VARCHAR(255) NULL',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
