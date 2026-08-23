-- INST-5a — one handset cannot be financed on two live plans at once.
--
-- WHY THIS IS A DATABASE CONSTRAINT AND NOT A SERVICE CHECK
-- A check-then-insert in application code is exactly how two cashiers at two tills finance the same IMEI in
-- the same second: both read "no live plan", both insert. The window is small and the consequence is a handset
-- the shop believes it is owed twice. Only the database can close it.
--
-- WHY IT IS NOT A REMOTE CHECK EITHER
-- The obvious design is to ask inventory-service whether the serial is already out. That check would fail OPEN
-- the moment inventory-service is slow or down — the sale would go through and the guarantee would be worth
-- nothing precisely when the shop is busiest. The authoritative constraint therefore lives HERE, in the same
-- transaction as the sale that needs it. inventory-service's serial_unit registry owns the unit's LIFECYCLE
-- (in stock / sold / repossessed / scrapped); it is not what makes the sale safe.
--
-- WHY A PLAIN UNIQUE (organization_id, asset_ref) WOULD BE A BUG
-- It would block the shop from ever re-selling a handset it legitimately repossessed, because the CANCELLED
-- plan still holds that serial. The rule is "not on two LIVE plans", never "never twice" — a repossessed unit
-- going back on the shelf and out again is the whole point of INST-5.
--
-- MySQL has no partial/filtered unique index, so the standard emulation is a STORED generated column that is
-- NULL for the rows the constraint must ignore. NULLs do not collide in a MySQL unique index, so:
--   * many plans with no serial at all       -> live_asset_ref NULL -> no collision
--   * many CANCELLED/COMPLETED plans, same serial -> live_asset_ref NULL -> no collision
--   * two ACTIVE/DEFAULTED plans, same serial     -> COLLISION, refused. This is the safety property.
--
-- Because the column is STORED and derived from `status`, it re-computes on UPDATE: the moment a plan is
-- cancelled the serial FREES ITSELF. Nothing has to remember to release it, which is the failure mode a
-- release flag maintained by application code would have.

ALTER TABLE installment_plan
    ADD COLUMN live_asset_ref VARCHAR(64)
        GENERATED ALWAYS AS (
            CASE WHEN status IN ('ACTIVE', 'DEFAULTED') THEN asset_ref ELSE NULL END
        ) STORED;

ALTER TABLE installment_plan
    ADD CONSTRAINT uq_plan_live_asset UNIQUE (organization_id, live_asset_ref);

-- Reading back "which plan holds this serial" — the lookup behind the refusal message, which must name the
-- plan the cashier should go and look at rather than just saying no.
CREATE INDEX idx_plan_asset_ref ON installment_plan (organization_id, asset_ref);
