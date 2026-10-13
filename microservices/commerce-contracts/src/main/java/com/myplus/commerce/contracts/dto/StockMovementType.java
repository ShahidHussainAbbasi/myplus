package com.myplus.commerce.contracts.dto;

/**
 * SSR-0a (doc slices/110-stock-ledger-ssr.md) — WHY stock moved, as a caller tells inventory-service. Strings, not an
 * enum on the wire: an older caller that sends nothing gets the endpoint's default, and a newer value never breaks an
 * older reader. Stored as-is in {@code stock_movement.movement_type} (VARCHAR(24)).
 */
public final class StockMovementType {
    private StockMovementType() {}

    /** Go-live balance brought forward, one per batch (SSR-0b). */
    public static final String OPENING = "OPENING";
    /** Stock entered directly, with no bill (product screen / opening-stock entry). */
    public static final String MANUAL_RECEIPT = "MANUAL_RECEIPT";
    public static final String PURCHASE = "PURCHASE";
    /** A purchase bill edited: its quantity delta. */
    public static final String PURCHASE_EDIT = "PURCHASE_EDIT";
    /** Goods sent back to the supplier (incl. a voided bill). */
    public static final String PURCHASE_RETURN = "PURCHASE_RETURN";
    /** Goods leaving on a sale — paid AND bonus units (the split lives on the sale line). */
    public static final String SALE = "SALE";
    /** A sale edited down: the excess back on the shelf. */
    public static final String SALE_EDIT = "SALE_EDIT";
    public static final String SALE_RETURN = "SALE_RETURN";
    public static final String SALE_VOID = "SALE_VOID";
    public static final String REPOSSESSION = "REPOSSESSION";
    /** A returned unit parked as not sellable — leaves sellable on-hand (it came in as SALE_RETURN). */
    public static final String QUARANTINE = "QUARANTINE";
    /** Stock correction (BLK-5). */
    public static final String ADJUSTMENT = "ADJUSTMENT";
    /** A receipt whose caller did not say why (an older caller). */
    public static final String RECEIPT = "RECEIPT";
}
