package com.myplus.business_service.dto;

import lombok.Data;

/**
 * R4 — one person standing behind a financed sale, as the sale screen sends them.
 *
 * <h3>⚠ This class has a TWIN in the monolith and they must change together</h3>
 * {@code com.web.dto.business.GuarantorDTO} is the same shape. The monolith binds the sale and
 * <b>re-serialises it</b> to business-service, so a field that exists only here is silently discarded in
 * transit — the sale succeeds and the guarantor simply never arrives. Design note F2.
 *
 * <h3>Only the name is mandatory</h3>
 * A cashier mid-sale with a customer waiting must not be blocked on a digit they can add this evening, and
 * {@code cnic} is a Pakistani identifier while this product ships in six languages. Its shape helps while
 * typing and is the key for recall; it is never a refusal.
 */
@Data
public class GuarantorDTO {

    /** GUARANTOR (the default) or WITNESS. Only GUARANTOR rows count towards the shop's requirement. */
    private String role;

    private String name;
    private String cnic;
    private String contact;
    private String address;

    /** Set when the cashier recalled somebody this shop already knows. An index, never authority. */
    private Long customerId;
}
