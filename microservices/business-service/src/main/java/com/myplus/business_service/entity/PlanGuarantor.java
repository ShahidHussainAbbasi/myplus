package com.myplus.business_service.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;

/**
 * R4 — a person who stands behind a financed sale.
 *
 * <h3>A stamped copy, not a pointer</h3>
 * {@code InstallmentPlan.guarantorPartyId} has said since V42 that "a guarantor is a Party with a role". That
 * ruling stands for the <b>link</b> and cannot stand for the <b>data</b>:
 *
 * <ul>
 *   <li><b>Evidence.</b> The shop's record must be what the guarantor signed on the day. A party row edited
 *       two years later by three different staff is not evidence of anything — the same reason
 *       {@code CustomerHistory.bookedByName} is written with the record rather than resolved when it is read.</li>
 *   <li><b>Availability.</b> The party bridge runs on a 1s/2s timeout that deliberately "fails fast to
 *       best-effort", which is right for a customer (already saved locally) and wrong for a guarantor who
 *       would otherwise exist <i>only</i> as a party. That timeout would commit a plan with no guarantor at
 *       all, silently.</li>
 * </ul>
 *
 * <p>Stamping here makes {@link #partyId} an <b>index</b> rather than the source of truth, so the best-effort
 * bridge becomes correct instead of dangerous. Nothing the shop relies on crosses a service boundary.
 *
 * <h3>Only the name is mandatory</h3>
 * A cashier mid-sale with a customer waiting must not be blocked on a digit they can add this evening, and
 * {@code cnic} is a Pakistani identifier while this product ships in six languages. The CNIC shape is help
 * while typing and the key for {@link #cnic}-based recall; it is never a refusal.
 */
@Data
@Entity
@Table(name = "plan_guarantor")
public class PlanGuarantor implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Both people this requirement asks for carry recourse. See {@link #role}. */
    public static final String GUARANTOR = "GUARANTOR";
    /** Attests rather than stands behind. Kept for contracts that carry one; not offered on the form. */
    public static final String WITNESS = "WITNESS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /**
     * {@code GUARANTOR} or {@code WITNESS}.
     *
     * <p><b>VARCHAR(16), matching {@code installment_plan.status} and {@code serial_unit.status}</b> — the
     * two columns of exactly this kind already in this service. V56 shipped it as a MySQL ENUM and
     * business-service refused to boot: under {@code ddl-auto=validate} a String field against an ENUM
     * column is <i>"found [enum], but expecting [varchar(16)]"</i>, and the service crash-looped nine times
     * while every screen behind it answered {@code 200 status:ERROR}. V58 corrects the column.
     *
     * <p><b>A column type is part of this class's contract, not a free choice in the migration.</b> That is
     * the third time this exact failure has been paid for — see also ONB-3's @Lob/TEXT and Notice.body.
     *
     * <p>{@code installments.guarantorsRequired} counts GUARANTOR rows only: a witness attests, and a shop
     * that recorded a witness believing it had recourse is worse off than one that recorded nobody.
     */
    @Column(name = "role", nullable = false, length = 16)
    private String role = GUARANTOR;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "cnic", length = 32)
    private String cnic;

    @Column(name = "contact", length = 64)
    private String contact;

    @Column(name = "address")
    private String address;

    /** Set when the guarantor was recalled from someone this shop already knows. An index, never authority. */
    @Column(name = "customer_id")
    private Long customerId;

    /** Filled by the party bridge, possibly later, possibly never. An index, never authority. */
    @Column(name = "party_id")
    private Long partyId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;
}
