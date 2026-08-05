-- Runs once, on FIRST MySQL container start (mounted at /docker-entrypoint-initdb.d/).
--
-- ⚠️ ONLY on first start — with an existing data volume this file is never re-read. Adding a service
-- here does NOT fix an already-deployed server: run the CREATE + GRANT by hand there as well, or the
-- new service dies at boot with "Access denied for user 'shahid'@'%' to database '…'" (MySQL 1044 —
-- a missing GRANT, not a bad password). Keep this list in step with every service's
-- application.yml datasource URL; audit and party were both missed once already.
-- The `shahid` application user is created by the container from MYSQL_USER/MYSQL_PASSWORD
-- (see docker-compose.yml) BEFORE this script runs; here we pre-create every per-service
-- database and grant `shahid` full rights on each. Pre-creating them means the services do
-- NOT need a global CREATE privilege (their JDBC createDatabaseIfNotExist just finds them).

-- POS / retail core
CREATE DATABASE IF NOT EXISTS myplusdb;              -- monolith + business-service
CREATE DATABASE IF NOT EXISTS myplusdb_auth;         -- auth-service
CREATE DATABASE IF NOT EXISTS myplusdb_catalog;      -- catalog-service
CREATE DATABASE IF NOT EXISTS myplusdb_inventory;    -- inventory-service
CREATE DATABASE IF NOT EXISTS myplusdb_finance;      -- finance-service (8094)
CREATE DATABASE IF NOT EXISTS myplusdb_audit;        -- audit-service (8095)
CREATE DATABASE IF NOT EXISTS myplusdb_party;        -- party-service (8096)

-- Other domain services (only used when the full stack is up)
CREATE DATABASE IF NOT EXISTS myplusdb_education;
CREATE DATABASE IF NOT EXISTS myplusdb_welfare;
CREATE DATABASE IF NOT EXISTS myplusdb_agriculture;
CREATE DATABASE IF NOT EXISTS myplusdb_pharma;
CREATE DATABASE IF NOT EXISTS myplusdb_marketplace;
CREATE DATABASE IF NOT EXISTS myplusdb_campaign;
CREATE DATABASE IF NOT EXISTS myplusdb_analytics;
CREATE DATABASE IF NOT EXISTS myplusdb_appointment;
-- (notification-service is stateless — no database.)

GRANT ALL PRIVILEGES ON myplusdb.*             TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_auth.*        TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_catalog.*     TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_inventory.*   TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_finance.*     TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_audit.*       TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_party.*       TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_education.*    TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_welfare.*      TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_agriculture.*  TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_pharma.*       TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_marketplace.*  TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_campaign.*     TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_analytics.*    TO 'shahid'@'%';
GRANT ALL PRIVILEGES ON myplusdb_appointment.*  TO 'shahid'@'%';
FLUSH PRIVILEGES;
