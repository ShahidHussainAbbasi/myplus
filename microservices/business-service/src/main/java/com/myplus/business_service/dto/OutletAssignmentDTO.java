package com.myplus.business_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OMS O7 D6a — an outlet as the OWNER's territory screen needs it: identity, plus who covers it.
 *
 * <h3>Why not {@link OutletDTO}</h3>
 * That one is the REP's picker and deliberately carries no more than the shop's identity plus whether it is
 * theirs. Telling a rep which colleague holds which outlet is a different disclosure entirely — a customer
 * list is a distributor's most poachable asset — so the holder appears only on the read that owners and
 * admins alone may call.
 *
 * <h3>Why the rep's ID and not their name</h3>
 * The screen already fetches {@code /api/auth/org/users} to fill its dropdown, so it can join the names
 * itself; having business-service resolve them would put an auth-service round trip on a read whose only job
 * is to draw a table.
 *
 * <p>And unlike {@code booked_by_name}, this is deliberately NOT stamped. That field is frozen at write time
 * because an issued order outlives its staff and a document must not change after the fact. An assignment is
 * the opposite kind of fact — it is current state, and if a rep is renamed the assignment should say so.
 *
 * <p>{@code assignedRepUserId} is null for an outlet nobody covers, which under the D2d rule means every rep
 * can see it. Absent is not the same as hidden.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OutletAssignmentDTO {

    private Long id;
    private String name;
    private String contact;
    private Long assignedRepUserId;
}
