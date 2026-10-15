-- HMS H2 — a doctor of the clinic linked to a login (docs/hms-phase1-design.md §4g). Additive only.
--
-- user_id: the auth-service user this doctor IS. NULL = not linked (the Doctor set / owner still cover the queue).
-- One login is at most one doctor of a clinic: uq_provider_user (NULLs never collide, so unlinked rows are free).
ALTER TABLE clinic_provider
    ADD COLUMN user_id   BIGINT   NULL,
    ADD COLUMN linked_at DATETIME NULL,
    ADD COLUMN linked_by BIGINT   NULL,
    ADD UNIQUE KEY uq_provider_user (organization_id, user_id);
