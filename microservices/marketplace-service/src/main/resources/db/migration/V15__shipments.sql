-- OMS O5b — an order stops being all-or-nothing.
--
-- Until now an order shipped whole or not at all: order_items recorded `quantity` with nowhere to say "3 of 5
-- shipped", and "SHIPPED" was a word a packer typed with nothing recording what left, when, or with what
-- tracking. A merchant with five items and three in stock had to ship everything or nothing.
--
-- NOTE FOR ANYONE EXTENDING THIS: a shipment moves NO stock. O1 decrements inventory when the sale is recorded;
-- these tables record what physically left against stock that is already gone from the books. Decrementing
-- again on dispatch would silently halve the shop's inventory and would look like a reasonable thing to do.

-- 1) How much of each line has actually gone out. Defaults to 0; every pre-O5b line is either wholly shipped or
--    wholly not, and the backfill below sets the shipped ones so returns keep reversing the right number.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='order_items' AND COLUMN_NAME='quantity_shipped')=0,
    'ALTER TABLE order_items ADD COLUMN quantity_shipped INT NOT NULL DEFAULT 0 AFTER quantity', 'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Backfill: an order already past dispatch had, by definition, all of it shipped. Without this, returns on
-- historical orders would reverse 0 (see §2.4 of the slice) — silently returning nothing to stock.
UPDATE order_items oi
   JOIN orders o ON o.id = oi.order_id
   SET oi.quantity_shipped = oi.quantity
 WHERE oi.quantity_shipped = 0
   AND o.fulfilment_status IN ('SHIPPED','DELIVERED','RETURN_REQUESTED','RETURNED');

-- 2) The new derived state. fulfilment_status is a real MySQL ENUM, so a Java constant alone fails at runtime
--    with "Data truncated for column 'fulfilment_status'" — the same ALTER V7 took for the return lifecycle.
SET @sql := IF((SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='orders'
                  AND COLUMN_NAME='fulfilment_status' AND COLUMN_TYPE LIKE '%PARTIALLY_SHIPPED%')=0,
    'ALTER TABLE orders MODIFY fulfilment_status enum(''NEW'',''PACKED'',''PARTIALLY_SHIPPED'',''SHIPPED'',''DELIVERED'',''CANCELLED'',''RETURN_REQUESTED'',''RETURNED'') DEFAULT NULL',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) The parcels themselves.
CREATE TABLE IF NOT EXISTS shipment (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       NOT NULL,
    user_id          BIGINT       NULL,
    order_id         BIGINT       NOT NULL,
    -- Per-org series (SHP-000045), allocated MAX+1 inside the creating transaction and made race-safe by the
    -- unique key below — the same allocation invoice_seq / order_seq / quote_seq use.
    shipment_seq     BIGINT       NOT NULL,
    shipment_no      VARCHAR(32)  NOT NULL,
    carrier          VARCHAR(120) NULL,
    tracking_number  VARCHAR(120) NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'DISPATCHED',
    shipped_at       DATETIME(6)  NULL,
    note             VARCHAR(500) NULL,
    version          BIGINT       NULL,
    created_at       DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_shipment_org_seq (organization_id, shipment_seq),
    KEY idx_shipment_order (order_id),
    KEY idx_shipment_org (organization_id),
    CONSTRAINT fk_shipment_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 4) What was in each parcel. Quantity is per ORDER LINE, so two parcels can each carry part of one line.
CREATE TABLE IF NOT EXISTS shipment_line (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    shipment_id    BIGINT NOT NULL,
    order_item_id  BIGINT NOT NULL,
    quantity       INT    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_shipment_line_shipment (shipment_id),
    KEY idx_shipment_line_item (order_item_id),
    CONSTRAINT fk_shipment_line_shipment FOREIGN KEY (shipment_id) REFERENCES shipment (id),
    CONSTRAINT fk_shipment_line_item FOREIGN KEY (order_item_id) REFERENCES order_items (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
