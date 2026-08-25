package com.myplus.business_service.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PERF — a customer as a PICKER needs them, and nothing more.
 *
 * <h3>What it replaces, and what it does not</h3>
 * The sale screen's customer dropdown and the report filters both read {@code /getUserCustomer}, which
 * returns the full {@link CustomerDTO}: 22 fields per row, 441 rows, ~215KB, on every open of the sale
 * screen — and unpaginated, so it grows with the tenant rather than staying still.
 *
 * <p>Six of those fields are used. The dropdown reads {@code customerId}, {@code name}, {@code contact},
 * {@code dueAmount}, {@code creditLimit} and {@code customerType} onto its options; the report filter reads
 * two of them. The other sixteen are serialised, sent and discarded.
 *
 * <p><b>{@code /getUserCustomer} is deliberately untouched.</b> Forty Cypress specs read it, along with
 * screens that legitimately need the whole record — {@code partyId}, addresses, licence details. Slimming a
 * general-purpose read because two of its callers are pickers would break the rest to speed those two up.
 * This is the PERF-8 shape: a lean projection ALONGSIDE the full read, exactly as the product picker got.
 *
 * <h3>Why a constructor projection rather than a trimmed DTO</h3>
 * The bytes are only half the cost. Selecting these six columns means Hibernate never builds 441 managed
 * {@code Customer} entities, never touches the {@code customerHistory} association, and ModelMapper never
 * runs — the row arrives as this object straight from the result set. Trimming fields on the way out would
 * have saved the bandwidth and kept all the work.
 *
 * <h3>What is NOT on it, on purpose</h3>
 * No address, no CNIC, no licence number, no partyId. A picker is not a customer master, and a projection
 * that grew "because we had it" is how a dropdown quietly becomes a data export.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerOptionDTO {

    private Long customerId;
    private String name;
    private String contact;

    /**
     * Both carried because the sale screen shows the customer's standing as they are chosen — that is the
     * point at which an over-limit account has to be caught, not afterwards.
     */
    private BigDecimal dueAmount;
    private BigDecimal creditLimit;

    /**
     * B2B vs walk-in: the dropdown branches on it, so it travels.
     *
     * <p>The ENUM, not a String — and that distinction cost a crash-loop. A JPQL constructor projection
     * matches on TYPE, so {@code String customerType} left Hibernate with no matching constructor and the
     * service failed at startup with "Missing constructor for type 'CustomerOptionDTO'". It is declared
     * exactly as {@link CustomerDTO} declares it, which also keeps the wire format identical: Jackson
     * serialises the enum to its name, so the dropdown's {@code data-customer-type} is unchanged.
     */
    private com.myplus.business_service.entity.enums.CustomerType customerType;
}
