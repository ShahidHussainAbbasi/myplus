package com.myplus.business_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * B2B Phase 3g — an owner-designed layout for a printed sale document (V35).
 *
 * <p>{@code profileJson} holds a <b>declarative Document Profile</b>: paper size, header field groups, the
 * line columns with their labels/widths/alignment, which totals rows print, and the footer. It is
 * deliberately <b>not</b> a template language and <b>not</b> markup — every field is referenced by a KEY
 * that must exist in the renderer's whitelist ({@code DocumentRenderer.FIELD_WHITELIST}). That boundary is
 * what makes an owner-authored invoice safe to print and hand to a third party: an owner controls presence,
 * order, label, width and alignment, never code. It also keeps built-in labels translatable across all six
 * locales, and keeps layouts upgradeable — a new field is a new whitelist entry, not a broken tenant
 * template. {@link com.myplus.business_service.service.DocumentProfileValidator} enforces it on save.
 *
 * <p><b>Why a table rather than an {@code org_setting} row:</b> {@code org_setting.setting_value} is
 * {@code VARCHAR(500)} — a layout does not fit — and {@code SettingsStore.findAll()} loads every override
 * each time the Configuration screen opens, which would drag template bodies into an unrelated read.
 * Settings keep only the BINDING ({@code pos.document.tradeTemplateId} / {@code retailTemplateId}); the
 * bodies live here.
 *
 * <p>Org-scoped like every other tenant row. A tenant with no rows falls back to a built-in preset, which
 * reproduces today's receipt byte for byte — which is what makes this whole phase additive.
 */
@Entity
@Table(name = "document_template", uniqueConstraints = {
        @UniqueConstraint(name = "uq_document_template",
                columnNames = {"organization_id", "doc_type", "channel", "name"})})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DocumentTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;        // tenant scope

    @Column(name = "user_id")
    private Long userId;                // audit: who created/last changed it

    /** What kind of document this lays out. Only {@code SALE} today; the column exists so a purchase order
     *  or a statement can join later without another migration. */
    @Builder.Default
    @Column(name = "doc_type", nullable = false, length = 24)
    private String docType = "SALE";

    /** {@code B2B} | {@code B2C} | null = usable for either. Matches the channel derived from the buyer's
     *  {@code Customer.customerType} — the same field that drives pricing and the credit limit. */
    @Column(name = "channel", length = 8)
    private String channel;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Lob
    @Column(name = "profile_json", nullable = false, columnDefinition = "TEXT")
    private String profileJson;

    /** The template this org uses for its channel when no explicit binding names another. */
    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    /** Bumped on every save. Not history — a cheap way for a cached resolver to notice a change. */
    @Builder.Default
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
