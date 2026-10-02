-- DR-1 — match keys, so one business partner is found however their phone was typed.
--
-- WHY: matching compared the RAW contact text. The mobile validator accepts 03…, +923…, 00923…, with or without
-- a dash, so one person registered as a customer ("0300-1234567") and as a supplier ("+923001234567") became TWO
-- parties, and the contact view never showed them as one partner.
--
-- contact_key  last 10 digits of contact (PartyKeys.phoneKey). Derived by the entity on every write; backfilled here.
-- tax_key      CNIC / NTN digits (PartyKeys.taxKey). Written by the bridge from now on; nothing to backfill —
--              party never stored a tax id before.
--
-- ADDITIVE ONLY. uq_party_org_contact (raw text) is KEPT (standards D4/D5: no schema removal on inference). Parties
-- that already split on format stay split; the owner's "possible duplicates" list shows them, and linking them is
-- a deliberate action (DR-2), never automatic.
--
-- No UNIQUE on contact_key: existing tenants may already hold two parties with one key (the very split this fixes),
-- and a unique index would refuse to build. Indexed for the match query instead.

ALTER TABLE party ADD COLUMN contact_key VARCHAR(16) NULL AFTER email;
ALTER TABLE party ADD COLUMN tax_key     VARCHAR(32) NULL AFTER contact_key;

UPDATE party
   SET contact_key = RIGHT(REGEXP_REPLACE(contact, '[^0-9]', ''), 10)
 WHERE contact IS NOT NULL
   AND CHAR_LENGTH(REGEXP_REPLACE(contact, '[^0-9]', '')) >= 10;

CREATE INDEX idx_party_org_contact_key ON party (organization_id, contact_key);
CREATE INDEX idx_party_org_tax_key     ON party (organization_id, tax_key);

-- A role link MOVES when a record is re-linked to another partner (DR-1 re-link on edit, DR-2 link/unlink): the
-- old partner's row for the same (module, role, local_id) is deleted. This serves that lookup.
CREATE INDEX idx_role_link_org_local ON party_role_link (organization_id, module, role, local_id);
