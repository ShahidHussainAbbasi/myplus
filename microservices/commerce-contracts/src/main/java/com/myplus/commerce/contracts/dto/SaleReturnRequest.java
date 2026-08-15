package com.myplus.commerce.contracts.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OMS O7 D4 — return part of an invoice: the goods a shop refused at the door.
 *
 * <p><b>One body, no query parameters</b> — and that is not a style choice. A Spring HTTP-interface client
 * encodes {@code @RequestParam} as FORM DATA on a POST, which cannot coexist with a {@code @RequestBody}; the
 * first draft of this contract mixed the two and every call failed at runtime. It was also the only method in
 * the whole contract set that did, which is the tell: {@link SaleRecordRequest} carries everything in one body,
 * and so does this.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaleReturnRequest {

    /** The invoice the goods are coming back off. Resolved within the CALLER's tenant by the receiver. */
    private String invoiceNo;

    /** Why — recorded on the credit note, so a shop's refusal is explainable months later. */
    private String reason;

    private List<SaleReturnLine> lines;
}
