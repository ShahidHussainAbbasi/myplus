package com.myplus.catalog.dto;

/**
 * Answer to "is this product name already registered?" — the server-side duplicate check the Product form
 * fires when the Name field loses focus.
 *
 * <p>ADVISORY, not a rejection. Two products may legitimately share a name across categories, manufacturers or
 * pack sizes, so this reports the namesake and lets the operator decide; {@code ProductService.create} still
 * rejects only a duplicate SKU. The matched product is named (id/sku/active) so the form can offer "edit this
 * one instead" rather than just telling the user they are wrong and leaving them to go find it.
 *
 * @param exists whether a different product in this tenant already carries the name
 * @param id     the matched product's id (null when {@code exists} is false)
 * @param name   the matched product's name AS STORED — the casing/spacing may differ from what was typed
 * @param sku    the matched product's SKU, or null when it has none
 * @param active false when the namesake is deactivated (it still owns the name downstream)
 */
public record NameCheckDTO(boolean exists, Long id, String name, String sku, Boolean active) {

    /** No namesake — the single "all clear" instance. */
    public static NameCheckDTO none() {
        return new NameCheckDTO(false, null, null, null, null);
    }
}
