-- party-service baseline: the shared party/contact master. Owns common identity only; each module keys its domain
-- data by this id (the partyId). De-dup within a tenant on (organization_id, contact) so the same person entered in
-- POS / pharmacy / education resolves to ONE party.

CREATE TABLE party (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NULL,
    user_id          BIGINT       NULL,
    party_type       VARCHAR(20)  NULL,       -- CUSTOMER | VENDOR | STUDENT | DONOR | PATIENT | OTHER
    name             VARCHAR(255) NOT NULL,
    contact          VARCHAR(64)  NULL,       -- phone/mobile — primary de-dup key within an org
    email            VARCHAR(255) NULL,
    address          VARCHAR(255) NULL,
    notes            VARCHAR(500) NULL,
    active           BIT(1)       DEFAULT NULL,
    created_at       DATETIME     NULL,
    updated_at       DATETIME     NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_party_org_contact UNIQUE (organization_id, contact),
    KEY idx_party_org_email (organization_id, email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
