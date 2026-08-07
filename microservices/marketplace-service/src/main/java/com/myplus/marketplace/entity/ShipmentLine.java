package com.myplus.marketplace.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * OMS O5b — how much of ONE order line went out in ONE parcel.
 *
 * <p>Keyed on the order line rather than the product, so two parcels can each carry part of the same line: an
 * order for five of something can ship as three and two, and each shipment says which three and which two.
 */
@Entity
@Table(name = "shipment_line")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
// Both excluded: a line referencing its parent, which holds a list of lines, recurses in equals/hashCode and
// toString. SalesQuoteLine needed the same treatment for the same reason.
@EqualsAndHashCode(exclude = "shipment")
@ToString(exclude = "shipment")
public class ShipmentLine {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning side, so the FK is written by the line's own INSERT.
     *
     * <p>This started as a unidirectional {@code @OneToMany @JoinColumn} on {@link Shipment} — the shape
     * {@code Order}/{@code OrderItem} uses. That makes Hibernate insert the child with a NULL FK and UPDATE it
     * immediately after, which {@code order_items.order_id} tolerates only because it is nullable.
     * {@code shipment_line.shipment_id} is NOT NULL, so the first insert failed with
     * <i>"Field 'shipment_id' doesn't have a default value"</i>. Owning the relationship here writes the FK in
     * one statement and keeps the column non-null, rather than weakening the schema to suit the mapping.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false)
    private Shipment shipment;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private Integer quantity;
}
