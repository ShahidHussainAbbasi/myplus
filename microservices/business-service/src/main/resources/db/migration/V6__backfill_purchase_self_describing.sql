-- M3c.4b (slice 84): backfill the purchase row's self-describing snapshot columns (V4 added them) from the linked
-- Stock for HISTORICAL rows, so the purchase grid renders batch/rate WITHOUT reading the local Stock FK. V5 only
-- backfilled product_id; this fills the rest. Idempotent (only legacy rows that still carry a stock_id and were never
-- self-described, gated on item_id IS NULL) and safe to re-run / no-op on an empty DB.
--
-- Column map: stock.batch_no→batch_no, stock.item_id→item_id, stock.batch_purchase_rate→bpurchase_rate,
-- stock.batch_sale_rate→bsell_rate, stock.batch_purchaseDiscount(Type)→bpurchase_discount(_type),
-- stock.batch_saleDiscount(Type)→bsell_discount(_type), stock.bexp_date→bexp_date.

UPDATE purchase p
  JOIN stock st ON p.stock_id = st.stock_id
  SET p.item_id                 = st.item_id,
      p.batch_no                = st.batch_no,
      p.bpurchase_rate          = st.batch_purchase_rate,
      p.bsell_rate              = st.batch_sale_rate,
      p.bpurchase_discount      = st.batch_purchaseDiscount,
      p.bsell_discount          = st.batch_saleDiscount,
      p.bpurchase_discount_type = st.batch_purchaseDiscountType,
      p.bsell_discount_type     = st.batch_saleDiscountType,
      p.bexp_date               = st.bexp_date
  WHERE p.stock_id IS NOT NULL AND p.item_id IS NULL;
