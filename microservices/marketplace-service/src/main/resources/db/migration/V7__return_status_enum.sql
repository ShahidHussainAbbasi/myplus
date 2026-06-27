-- Flyway: widen orders.fulfilment_status for the return lifecycle (slice 71, E10). Hibernate maps the
-- @Enumerated(STRING) FulfilmentStatus to a NATIVE MySQL enum (values sorted alphabetically), so adding
-- RETURN_REQUESTED / RETURNED to the Java enum requires an ALTER…MODIFY — ddl-auto won't widen it (validate),
-- and inserting a new value into the old 5-value enum fails with "Data truncated". MODIFY is naturally idempotent.
-- The value list + order match Hibernate's generated column so `validate` accepts it.
ALTER TABLE orders MODIFY fulfilment_status
  enum('CANCELLED','DELIVERED','NEW','PACKED','RETURNED','RETURN_REQUESTED','SHIPPED') DEFAULT NULL;
