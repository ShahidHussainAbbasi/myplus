-- Multi-tenancy: analytics-service had NO tenant column, so report definitions and aggregated metrics were
-- global — any authenticated user could read/modify/delete any tenant's reports, and a metric query returned
-- every tenant's numbers. Add organization_id (nullable → standard org-scope predicate with legacy fallback).
--
-- report_executions is NOT stamped: it is reached only through its parent report_definition, whose lookup is
-- now org-scoped, so it inherits the boundary.
-- dashboard_widgets is PER-USER (user_id, globally unique) — personal layout, not tenant-shared — so it is
-- isolated by user_id (enforced in DashboardService), not by org.

ALTER TABLE report_definitions ADD COLUMN organization_id BIGINT NULL;
ALTER TABLE aggregated_metrics ADD COLUMN organization_id BIGINT NULL;

CREATE INDEX idx_report_definitions_org ON report_definitions (organization_id);
CREATE INDEX idx_aggregated_metrics_org ON aggregated_metrics (organization_id);
