-- R4 — the people who stand behind a financed sale.
--
-- THE GAP THIS CLOSES
-- 211 live installment plans carry money owed and NOT ONE names a guarantor, because there was nowhere to put
-- one. A shop that finances a handset, a motorcycle or a fridge and cannot say who else stands behind the debt
-- has no recourse at all when the buyer stops paying — which is the moment the record exists for.
--
-- WHY THE IDENTITY IS STAMPED HERE AND NOT JUST LINKED
-- InstallmentPlan.guarantor_party_id has said since V42 that "a guarantor is a Party with a role; party-service
-- owns it". That ruling stands for the LINK. It cannot stand for the DATA, for two reasons:
--
--   1. EVIDENCE. The shop's record must be what the guarantor signed on the day. A party row edited two years
--      later by three different staff is not evidence of anything. This is the same reason an audit trail
--      stamps bookedByName instead of resolving a user id when it is read.
--   2. AVAILABILITY. business-service reaches party-service on a 1s/2s timeout that deliberately "fails fast
--      to best-effort" — right for a customer bridge, because the customer is already saved locally. If the
--      guarantor existed ONLY as a party, that same timeout would commit a plan with no guarantor at all,
--      silently, exactly like the gl_outbox field that vanished because one of five places was not updated.
--
-- Stamping the copy here makes the party link an INDEX rather than the source of truth, so best-effort becomes
-- correct instead of dangerous. party_id stays NULL until the bridge succeeds, and nothing the shop relies on
-- crosses a service boundary.
--
-- WHY installment_plan.guarantor_party_id IS NOT DROPPED
-- It is wired to nothing and holds 0 rows in dev. That is NOT permission to drop it — SAAS-BUILD-STANDARDS D5:
-- "'Unmapped' and 'empty on dev' are not the same claim, and neither survives contact with production." It has
-- been counted in exactly one environment. It is left alone and NOT written either, because populating both
-- would create two answers to one question. A later slice may drop it once production has been counted.
--
-- NO UNIQUE CONSTRAINT ON cnic, DELIBERATELY
-- One person guaranteeing several plans is normal, and in a shop that finances it is common — a shopkeeper's
-- brother-in-law guarantees twenty sales. Uniqueness here would refuse a legitimate second sale.
--
-- ROLE: every row this requirement asks for is a GUARANTOR — two people who both carry recourse, not a
-- guarantor and a witness. WITNESS is in the enum because these contracts do sometimes carry one and adding it
-- later would need an ALTER ... MODIFY on a live table; it is not offered on the form, and the
-- guarantorsRequired count only ever counts GUARANTOR rows.

CREATE TABLE IF NOT EXISTS plan_guarantor (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NOT NULL,
    plan_id          BIGINT       NOT NULL,
    -- WRONG AS SHIPPED, corrected by V58 to VARCHAR(16): under ddl-auto=validate a String field against
    -- an ENUM column stops the service booting. Left as it is because this migration has already run
    -- and its checksum is recorded -- editing it would break Flyway in every environment that applied
    -- it. A fresh deploy runs V56 then V58 and lands in the same place.
    role             ENUM('GUARANTOR','WITNESS') NOT NULL DEFAULT 'GUARANTOR',

    -- STAMPED at write, never derived on read. See above.
    -- Only `name` is NOT NULL: a cashier mid-sale with a customer waiting must not be blocked on a digit they
    -- can add this evening, and CNIC is a Pakistani identifier while this product ships in six languages.
    name             VARCHAR(255) NOT NULL,
    cnic             VARCHAR(32)  NULL,
    contact          VARCHAR(64)  NULL,
    address          VARCHAR(255) NULL,

    -- Both links, both NULLABLE, both only ever an INDEX.
    customer_id      BIGINT       NULL,
    party_id         BIGINT       NULL,

    created_at       DATETIME     NOT NULL,
    created_by       BIGINT       NULL,

    PRIMARY KEY (id),
    -- The only two reads this table has: one plan's guarantors, and this org's recent/recalled ones.
    KEY ix_plan_guarantor_plan (plan_id),
    KEY ix_plan_guarantor_org_cnic (organization_id, cnic)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
