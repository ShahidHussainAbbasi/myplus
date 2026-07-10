-- F2 (statements/aging): index-only migration (no columns). The aging reads scan the tenant's OPEN docs
-- (due_amount < 0) filtered by organization; index (organization_id, due_amount) so those scoped scans stay fast
-- as invoice/purchase history grows. Purchase(vender_id, due_amount) already exists from V14.
CREATE INDEX idx_ch_org_due       ON customer_history (organization_id, due_amount);
CREATE INDEX idx_purchase_org_due ON purchase          (organization_id, due_amount);
