-- EX-6 — expense claims. A claim is an expense voucher paid from the member's own pocket (paid_from EMPLOYEE): it stays
-- a DRAFT while it waits, and APPROVING it posts it (Dr the category / Cr 2300 Employee Reimbursement Payable).
-- claim_status: SUBMITTED → APPROVED | REJECTED | WITHDRAWN. NULL for every voucher that is not a claim.
ALTER TABLE expense_voucher
    ADD COLUMN claim_status   VARCHAR(16)  NULL,
    ADD COLUMN claimant_name  VARCHAR(160) NULL,
    ADD COLUMN decided_by     BIGINT       NULL,
    ADD COLUMN decided_at     DATETIME     NULL,
    ADD COLUMN decision_note  VARCHAR(255) NULL,
    ADD KEY idx_expense_voucher_claim (organization_id, claim_status);
