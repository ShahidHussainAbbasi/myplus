-- HMS H4 — the pharmacy branch a doctor's prescriptions are routed to (client ruling L-4). Set by the owner / an
-- admin on the Doctors screen; NULL = not routed (the prescription is every branch's, as before H4).
ALTER TABLE clinic_provider ADD COLUMN store_id BIGINT NULL;
