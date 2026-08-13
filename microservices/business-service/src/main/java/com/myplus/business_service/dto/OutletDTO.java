package com.myplus.business_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OMS O7 D2d — an outlet as the booking screen's picker needs it: <b>identity only</b>.
 *
 * <h3>Why not CustomerDTO</h3>
 * Least privilege. {@code CustomerDTO} carries {@code dueAmount}, {@code creditLimit}, {@code creditBalance},
 * payment terms and the account-hierarchy links — the data the customer-master visibility rule exists to
 * protect. A rep choosing which shop they are standing in needs a name, not a balance sheet, and returning
 * the whole record "because we had it" is how a picker quietly becomes a financial report.
 *
 * <p>The rep can still see an outlet's credit position — through {@code /creditStanding}, which is a separate,
 * deliberate, org-scoped call for one customer at a time. Two different questions, two different endpoints,
 * each answering only what it was asked.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OutletDTO {

    private Long id;
    private String name;
    private String contact;
    private String address;

    /**
     * Is this outlet explicitly on the caller's round, or is it visible only because nobody has been assigned
     * it yet? The picker can then show a territory rep which shops are actually theirs without hiding the rest.
     */
    private boolean assignedToMe;
}
