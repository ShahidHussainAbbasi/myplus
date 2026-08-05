package com.myplus.business_service.service;

import com.myplus.business_service.entity.DocumentTemplate;
import com.myplus.business_service.repository.DocumentTemplateRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * B2B Phase 3g — CRUD and RESOLUTION for owner-designed document layouts (V35).
 *
 * <p>Resolution is a Chain of Responsibility, and the order is the point:
 *
 * <pre>
 *   explicit template id on the call     → reprint a specific layout / designer preview
 *   the org's binding for this channel   → pos.document.{trade,retail}TemplateId
 *   the org's default for (type, channel)→ is_default row, channel-specific before channel-agnostic
 *   nothing                              → null ⇒ the browser falls back to a built-in preset
 * </pre>
 *
 * <p>That last line is what keeps the whole phase additive: an org that never opens the designer resolves to
 * null and gets today's receipt, byte for byte.
 *
 * <p>Every read is org-scoped, and every by-id read goes through {@code findByIdScoped} — a layout can carry
 * a business's name and licence number, so reaching another tenant's would leak more than a layout.
 */
@Service
public class DocumentTemplateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentTemplateService.class);

    public static final String DOC_TYPE_SALE = "SALE";

    @Autowired
    private DocumentTemplateRepo repo;

    @Autowired
    private DocumentProfileValidator validator;

    public List<DocumentTemplate> list(Long orgId, Long userId) {
        return repo.findScoped(orgId, userId);
    }

    public Optional<DocumentTemplate> byId(Long id, Long orgId, Long userId) {
        return repo.findByIdScoped(id, orgId, userId);
    }

    /**
     * Create or update. The profile is validated and NORMALISED first — what gets stored is rebuilt from
     * what passed validation, never the caller's document echoed back.
     *
     * <p>An update reads the existing row through the scoped finder, so an id belonging to another tenant is
     * a "not found" rather than a silent cross-tenant overwrite.
     */
    @Transactional
    public DocumentTemplate save(DocumentTemplate incoming, Long orgId, Long userId) {
        String normalised = validator.validateAndNormalise(incoming.getProfileJson());

        DocumentTemplate row;
        LocalDateTime now = LocalDateTime.now();
        if (incoming.getId() != null) {
            row = repo.findByIdScoped(incoming.getId(), orgId, userId)
                    .orElseThrow(() -> new DocumentProfileValidator.InvalidProfileException("Layout not found."));
            row.setVersion(row.getVersion() == null ? 1 : row.getVersion() + 1);
        } else {
            row = new DocumentTemplate();
            row.setCreatedAt(now);
            row.setVersion(1);
            row.setOrganizationId(orgId);
            row.setUserId(userId);
        }
        row.setName(incoming.getName());
        row.setDocType(incoming.getDocType() == null ? DOC_TYPE_SALE : incoming.getDocType());
        row.setChannel(normaliseChannel(incoming.getChannel()));
        row.setProfileJson(normalised);
        row.setIsDefault(Boolean.TRUE.equals(incoming.getIsDefault()));
        row.setUpdatedAt(now);
        row = repo.save(row);

        // Exactly one default per (org, docType, channel). Demoting the others here rather than trusting the
        // caller to unset them keeps the invariant true even if two owners save at once from two screens.
        if (Boolean.TRUE.equals(row.getIsDefault())) {
            for (DocumentTemplate other : repo.findDefaultFor(orgId, row.getDocType(), row.getChannel())) {
                if (!other.getId().equals(row.getId())) {
                    other.setIsDefault(false);
                    repo.save(other);
                }
            }
        }
        return row;
    }

    @Transactional
    public boolean delete(Long id, Long orgId, Long userId) {
        Optional<DocumentTemplate> row = repo.findByIdScoped(id, orgId, userId);
        row.ifPresent(t -> repo.delete(t));
        return row.isPresent();
    }

    /**
     * The layout to print a sale with, as normalised JSON — or {@code null} to let the browser use a
     * built-in preset.
     *
     * <p>Best-effort by design: a layout is a preference, never a reason a shop cannot print. Any failure
     * here resolves to null, which is today's behaviour.
     *
     * @param channel {@code B2B} or {@code B2C}, derived from the buyer's customer type by the caller
     */
    public String resolveProfileJson(Long orgId, Long userId, String channel) {
        try {
            // DEVIATION from the design doc §2.5, deliberate: the design listed BOTH a
            // pos.document.{trade,retail}TemplateId setting AND an is_default flag. That is two mechanisms
            // for one job — and the settings one is the worse of the two, because it asks an owner to type a
            // numeric row id into a Configuration screen. `is_default` is set from the designer, where they
            // are actually looking at the layout they mean. One mechanism, no way for the two to disagree.
            List<DocumentTemplate> defaults = repo.findDefaultFor(orgId, DOC_TYPE_SALE, normaliseChannel(channel));
            return defaults.isEmpty() ? null : defaults.get(0).getProfileJson();
        } catch (Exception e) {
            LOGGER.error("Could not resolve a document template for org {} channel {}", orgId, channel, e);
            return null;
        }
    }

    private static String normaliseChannel(String channel) {
        if (channel == null) return null;
        String c = channel.trim().toUpperCase(java.util.Locale.ROOT);
        return ("B2B".equals(c) || "B2C".equals(c)) ? c : null;
    }
}
