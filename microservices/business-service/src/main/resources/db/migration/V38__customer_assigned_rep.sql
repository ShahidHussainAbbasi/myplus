-- OMS O7 D2d — WHICH field rep covers this outlet.
--
-- The industry model for field sales is TERRITORY: a rep sees the outlets assigned to them, not every customer
-- and not "the ones they happened to create". Every serious SFA/DSD system works this way (SAP DSD, Salesforce
-- Territory Management, and the products in this market), for three reasons that all apply to a distributor:
--
--   * a customer list is the most poachable asset a distributor owns — a rep who can browse the whole book can
--     walk it to a competitor, and territory is the control that prevents it;
--   * coverage KPIs (calls planned vs made, strike rate, productive calls) are undefined without an assigned
--     universe to measure against;
--   * commission attribution needs to know whose outlet it was.
--
-- ── Why a NEW column rather than reusing customer.user_id ────────────────────────────────────────────────
-- `user_id` is AUDIT — the entity says so ("user_id kept as audit"). The platform's "a plain user sees their
-- own rows" rule is built on it, which works in a shop because the person who creates a customer is the person
-- who serves them. In field sales that coincidence breaks: the COMPANY creates the outlet and a DIFFERENT
-- person sells to it. Overloading an audit field with an authorization meaning is what made the booking
-- screen's outlet picker come back empty for the only role that needs it.
--
-- ── Nullable, and empty means UNCONSTRAINED ───────────────────────────────────────────────────────────────
-- No backfill: nothing here can infer who covers an outlet, and inventing an assignment would silently hide
-- outlets from the reps who currently sell to them. An unassigned outlet is visible to every rep in the org —
-- the SAME rule this platform already applies to location grants ("EMPTY means no location constraint —
-- single-store / unassigned / legacy — so scoped reads apply no store filter and behave exactly as before").
-- So a small distributor works on day one having configured nothing, and a large one assigns outlets and the
-- picker narrows with no code change.
--
-- One primary rep per outlet: that is the standard shape. Many-to-many is over-modelling until beats exist
-- (D6), and a beat is a set of outlets, not a second owner of one.

SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND COLUMN_NAME='assigned_rep_user_id')=0,
    'ALTER TABLE customer ADD COLUMN assigned_rep_user_id BIGINT NULL AFTER user_id', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- The read this enables is "the outlets on MY round", which is org + rep. D3b: index the predicate the query
-- actually runs. Org first, as every scoped read on this platform is.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='customer' AND INDEX_NAME='idx_customer_org_rep')=0,
    'CREATE INDEX idx_customer_org_rep ON customer (organization_id, assigned_rep_user_id)', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
