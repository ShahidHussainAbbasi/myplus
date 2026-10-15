-- TEAM-1 (2026-10-13) — "Invited" on the Team list: a member the owner added who has not yet chosen a password
-- (Slack and Shopify show these as pending, with Resend). Stamped when the owner adds the member on Team; cleared
-- when the person sets their own password (the set-password link or a password change). Existing users stay NULL
-- = "not invited": whether an old account ever chose its password cannot be known, and nothing is guessed.
-- (No dollar-brace anywhere in this file: Flyway would read it as a placeholder, even in a comment.)

ALTER TABLE users ADD COLUMN invited_at DATETIME(6) NULL;
