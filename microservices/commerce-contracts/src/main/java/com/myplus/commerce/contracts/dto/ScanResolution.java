package com.myplus.commerce.contracts.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * U7 — what a scanned code MEANS: this many of this product, in this unit.
 *
 * <h3>Why this is not two more fields on {@link ProductRef}</h3>
 *
 * {@code ProductRef} is read by six services and describes a <b>product</b>. {@code soldUnit} and
 * {@code quantity} are not properties of a product — they are properties of <b>the code that was scanned</b>.
 * The same product answers "1 tablet" to one sticker and "12 packs" to another.
 *
 * <p>Widening {@code ProductRef} would put two fields on every consumer for which they are meaningless, and
 * one of them would eventually be read as though it were the product's own — which is how a pack size becomes
 * a scan quantity in a report nobody was watching.
 *
 * <p><i>A field belongs to the thing it describes. When it does not fit, the answer is a new type, not a
 * wider one.</i>
 *
 * <h3>The ordinary case</h3>
 * A manufacturer barcode resolves to {@code soldUnit = "PACK"}, {@code quantity = 1} — the same answer the
 * scan path has always acted on, now stated rather than assumed.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanResolution {

    /** The product this code identifies; null when the code matched nothing. */
    private ProductRef product;

    /** {@code PACK} or {@code LOOSE}. */
    @Builder.Default
    private String soldUnit = "PACK";

    /** How many of {@code soldUnit}. */
    @Builder.Default
    private Float quantity = 1f;

    /**
     * True when the code came from the shop's own sticker table rather than the product's own barcode/sku.
     *
     * <p>Carried so a till can say <i>why</i> a scan produced a single tablet, and so a gate can prove the
     * resolution ORDER rather than merely its result.
     */
    @Builder.Default
    private boolean ownSticker = false;
}
