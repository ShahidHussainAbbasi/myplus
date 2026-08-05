package com.myplus.business_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * B2B Phase 3g — the issuing business's identity as it prints at the top of a sale document.
 *
 * <p>This exists because before 3g the document header printed the VERTICAL PROFILE's brand — the literal
 * string "MyPlus Pharmacy" / "MyPlus POS" — which meant every tenant was handing their customers a document
 * carrying OUR brand instead of their own. A printed invoice must name the business that issued it.
 *
 * <p>Resolution order (see {@code SellController#letterheadFor}): the owner's {@code pos.document.*}
 * settings win, then the {@link com.myplus.business_service.entity.Store} the invoice was raised at. The
 * organisation's name is deliberately NOT fetched: it lives in auth-service, and a cross-service call on the
 * print path would put another service between a shop and its ability to print a receipt. When neither
 * source has a value the client falls back to the vertical brand, exactly as it does today.
 *
 * <p>{@code licenseNo}/{@code licenseExpiry} are the SELLER's trade or drug licence, which is why they are
 * settings rather than schema — a pharmacy has one licence, not one per invoice.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LetterheadDTO {

    private String businessName;

    private String addressLine1;

    private String addressLine2;

    private String phone;

    private String logoUrl;

    /** The seller's trade / drug licence, printed in the document header when the owner has set it. */
    private String licenseNo;

    private String licenseExpiry;

    /** The store this invoice was raised at — a fallback for the name, and printable in its own right. */
    private String storeName;
}
