-- Multi-tenancy: campaign-service had NO tenant column on any of its top-level tables, so every read/write
-- resolved by raw id and any authenticated user could reach any tenant's campaigns/audiences/segments/
-- templates. Add organization_id (nullable, so pre-existing rows fall back to their creator via the standard
-- org-scope predicate: org_id = :org OR (org_id IS NULL AND created_by = :user) — same as every other service).
--
-- Child tables (audience_members, campaign_logs) are NOT stamped: they are reached only through their parent
-- (audience / campaign), whose lookup is now org-scoped, so they inherit the boundary.
-- demo_request is the PUBLIC book-a-demo lead capture — deliberately tenant-less. Left untouched.

ALTER TABLE campaigns           ADD COLUMN organization_id BIGINT NULL;
ALTER TABLE campaign_audiences  ADD COLUMN organization_id BIGINT NULL;
ALTER TABLE campaign_segments   ADD COLUMN organization_id BIGINT NULL;
ALTER TABLE campaign_templates  ADD COLUMN organization_id BIGINT NULL;

CREATE INDEX idx_campaigns_org          ON campaigns          (organization_id);
CREATE INDEX idx_campaign_audiences_org ON campaign_audiences (organization_id);
CREATE INDEX idx_campaign_segments_org  ON campaign_segments  (organization_id);
CREATE INDEX idx_campaign_templates_org ON campaign_templates (organization_id);
