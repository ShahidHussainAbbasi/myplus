-- Per-org document numbers, allocated from a serialised counter instead of MAX(seq) + 1.
--
-- WHY MAX+1 HAD TO GO
-- Every per-org running number in this service was allocated `SELECT MAX(seq) + 1`. Two tills reading the
-- same maximum in the same moment both take the same number, and the UNIQUE constraint refuses the loser —
-- whose sale then died as "Transaction silently rolled back because it has been marked as rollback-only".
-- Twelve of those in one minute is what led here. Two cashiers on two tills is not an edge case; it is the
-- normal shape of a shop with more than one till.
--
-- WHY NOT JUST RETRY (which is what invoice + plan do today)
-- Retrying replays the whole operation, and that is only safe when the retried unit has no side effects
-- outside the database. It is true of the invoice write and the plan write. It is NOT true of a sale return,
-- which calls inventory to put the stock back BEFORE it allocates its credit-note number — a retry there
-- would restock the goods twice. Retry recovers from the collision; this prevents it.
--
-- WHY A COUNTER TABLE RATHER THAN AUTO_INCREMENT OR A DB SEQUENCE
-- The numbers are PER ORGANISATION: tenant 13 and tenant 20 both have a credit note 42, and must. MySQL has
-- no per-partition sequence, so the counter is a row keyed by (organization_id, doc_type) and the row lock
-- taken by the UPDATE is what serialises allocation. This is the same shape SAP's number ranges and Odoo's
-- ir.sequence use, for the same reason.
--
-- GAPLESS, BECAUSE THE ALLOCATION JOINS THE CALLER'S TRANSACTION
-- The bump is not committed independently. A return that fails after taking number 42 rolls the counter back
-- with everything else, so 42 is issued to the next caller instead of being burned. Credit notes are tax
-- documents and an unexplained gap in them is a question somebody has to answer at audit; this way there are
-- none to explain. The cost is that the row lock is held from allocation until commit, which is why callers
-- allocate LATE — immediately before the insert, never before a remote call.

CREATE TABLE IF NOT EXISTS org_document_seq (
    organization_id     BIGINT       NOT NULL,

    -- CREDIT_NOTE | DEBIT_NOTE | QUOTE (INVOICE and PLAN still use the retry path — see the design doc).
    -- VARCHAR, not ENUM: adding a value to a MySQL ENUM needs ALTER ... MODIFY, and without it the insert
    -- fails with "Data truncated" — a trap this platform has already paid for.
    doc_type            VARCHAR(16)  NOT NULL,

    -- The LAST number issued, not the next one. So a fresh counter starts at 0 and the first document is 1,
    -- which matches what MAX(seq) returned for an org with no documents and keeps the seeding below honest.
    next_val            BIGINT       NOT NULL DEFAULT 0,

    updated             DATETIME     DEFAULT NULL,

    PRIMARY KEY (organization_id, doc_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- SEEDING: start each counter at the highest number that organisation has already issued.
--
-- Getting this wrong is the one way this migration can corrupt data — a counter that starts below the
-- existing maximum hands out a number some document already carries, and the UNIQUE constraint turns that
-- into a failed sale on the shop's next return. MAX() per organisation, not per table, and COALESCE for the
-- org that has issued none.
--
-- Written as INSERT ... SELECT ... GROUP BY so it is exact for every tenant at once, and IGNORE so a re-run
-- (FlywayConfig repairs then migrates on every start) cannot double-apply it.

INSERT IGNORE INTO org_document_seq (organization_id, doc_type, next_val, updated)
SELECT organization_id, 'CREDIT_NOTE', COALESCE(MAX(credit_note_seq), 0), NOW()
FROM sale_return
WHERE organization_id IS NOT NULL
GROUP BY organization_id;

INSERT IGNORE INTO org_document_seq (organization_id, doc_type, next_val, updated)
SELECT organization_id, 'DEBIT_NOTE', COALESCE(MAX(debit_note_seq), 0), NOW()
FROM purchase_return
WHERE organization_id IS NOT NULL
GROUP BY organization_id;

INSERT IGNORE INTO org_document_seq (organization_id, doc_type, next_val, updated)
SELECT organization_id, 'QUOTE', COALESCE(MAX(quote_seq), 0), NOW()
FROM sales_quote
WHERE organization_id IS NOT NULL
GROUP BY organization_id;
