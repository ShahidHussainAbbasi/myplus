-- INST-1 — selling on a plan: the plan header and its dated obligations.
--
-- WHY THESE TABLES LIVE HERE AND NOT IN finance-service
-- An installment plan has no lifecycle away from the sale that created it, no integration surface of its own,
-- and no consumer that is not already a consumer of the invoice. The plan must be written in the SAME
-- transaction as the sale, and finance-service is deliberately reached through a best-effort client that a
-- ledger hiccup never blocks. So: arithmetic in the `common-installment` library, data in the service that
-- owns the receivable — the same split `CreditStore` settled for credit.
--
-- WHAT THIS DELIBERATELY DOES NOT ADD
-- No GL account, no posting event, no `gl_outbox` column. An installment sale posts EXACTLY what a credit
-- sale posts today (Dr AR / Cr Sales+Tax) and every receipt posts Dr Cash / Cr AR, unchanged. A new
-- `PostingEventRequest` field needs five separate copy points or it silently vanishes — which is how
-- `4200 Sales Discount` sat empty in every tenant for months while three specs stayed green. A design that
-- adds no field cannot reproduce that defect.
--
-- THE INVARIANT: SUM(installment.outstanding) for a plan == the plan invoice's outstanding balance. The plan
-- is a STRUCTURE OVER the existing receivable, never a second one. That equality is the INST-1 gate.

CREATE TABLE IF NOT EXISTS installment_plan (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,

    -- Per-org running number, mirroring invoice_seq (slice 22), credit_note_seq (3c) and quote_seq (P4b).
    -- The UNIQUE below is what makes MAX+1 allocation safe under concurrency; without it two cashiers
    -- selling at the same moment silently mint the same plan number.
    plan_seq            BIGINT       DEFAULT NULL,
    plan_no             VARCHAR(32)  DEFAULT NULL,

    organization_id     BIGINT       DEFAULT NULL,
    store_id            BIGINT       DEFAULT NULL,
    user_id             BIGINT       DEFAULT NULL,          -- audit: who sold it

    customer_id         BIGINT       NOT NULL,
    -- The financed sale. Not an FK: `customer_history` is MyISAM (V1 baseline), which silently ignores FK
    -- syntax, so declaring one would be documentation pretending to be a constraint.
    invoice_id          BIGINT       DEFAULT NULL,
    invoice_no          VARCHAR(32)  DEFAULT NULL,

    cash_price          DECIMAL(19,2) NOT NULL,
    down_payment        DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    -- INST-6. Stays 0.00 until `4400 Finance Income` exists: markup is finance income, not goods revenue,
    -- and folding it into the invoice value would tax financing. The column exists so the shape does not
    -- change later; `PlanTerms.validate()` refuses a non-zero value today.
    markup_amount       DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    financed_amount     DECIMAL(19,2) NOT NULL,

    installment_count   INT          NOT NULL,
    -- VARCHAR, never a MySQL ENUM: adding a value to an ENUM needs ALTER … MODIFY and fails as
    -- "Data truncated" until it runs. `customer.customer_type` carries the same rule for the same reason.
    frequency           VARCHAR(16)  NOT NULL DEFAULT 'MONTHLY',
    first_due_date      DATE         NOT NULL,
    final_due_date      DATE         DEFAULT NULL,

    -- DRAFT | ACTIVE | COMPLETED | DEFAULTED | CANCELLED | WRITTEN_OFF. Plan status IS stored because its
    -- transitions are decisions a person makes. Installment OVERDUE is NOT — see the installment table.
    status              VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',

    -- INST-1: the IMEI as free text, honest about being a LABEL and not a register. INST-5 replaces it with
    -- inventory-service's `serial_unit` FK. A shop that finances handsets and cannot say WHICH handset
    -- cannot repossess, honour a warranty, or tell two identical phones on two plans apart.
    asset_ref           VARCHAR(64)  DEFAULT NULL,
    serial_unit_id      BIGINT       DEFAULT NULL,          -- INST-5
    guarantor_party_id  BIGINT       DEFAULT NULL,          -- a Party with a role; party-service owns it

    notes               VARCHAR(500) DEFAULT NULL,
    dated               DATETIME     DEFAULT NULL,
    updated             DATETIME     DEFAULT NULL,

    -- Two cashiers converting the same cart must not produce two plans for one sale.
    version             BIGINT       DEFAULT 0,

    PRIMARY KEY (id),
    CONSTRAINT uq_plan_org_seq UNIQUE (organization_id, plan_seq),
    KEY idx_plan_org_customer (organization_id, customer_id),
    KEY idx_plan_org_status (organization_id, status),
    KEY idx_plan_invoice (organization_id, invoice_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS installment (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    plan_id             BIGINT       NOT NULL,
    organization_id     BIGINT       DEFAULT NULL,

    seq_no              INT          NOT NULL,              -- 1-based: the customer is told "3 of 6"
    due_date            DATE         NOT NULL,
    amount              DECIMAL(19,2) NOT NULL,
    paid_amount         DECIMAL(19,2) NOT NULL DEFAULT 0.00,

    -- POSITIVE while owing. Note this is the OPPOSITE sign convention to
    -- `customer_history.due_amount`, which stores (paid − bill) and is NEGATIVE while owing. The two must
    -- never be summed without normalising; the OpenDoc adapters are where that normalisation lives.
    outstanding         DECIMAL(19,2) NOT NULL,

    -- SCHEDULED | PARTIAL | PAID | WAIVED. There is deliberately NO 'OVERDUE' value: overdue is
    -- (due_date < today AND outstanding > 0), derived on read. Storing it would need a nightly job to flip
    -- rows, and the day that job does not run — a restart, a deploy, a Sunday — every screen shows stale
    -- truth. A derived predicate cannot go stale, and the reminder scanner uses the same predicate, so the
    -- screen and the reminder can never disagree.
    status              VARCHAR(16)  NOT NULL DEFAULT 'SCHEDULED',

    dated               DATETIME     DEFAULT NULL,
    updated             DATETIME     DEFAULT NULL,

    PRIMARY KEY (id),
    -- One row per position in a plan. This is what makes schedule generation idempotent under a retry.
    CONSTRAINT uq_installment_plan_seq UNIQUE (plan_id, seq_no),
    -- The reminder scanner's predicate and the "what is due" screen, in one index.
    KEY idx_installment_org_due (organization_id, due_date, status),
    KEY idx_installment_plan (plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
