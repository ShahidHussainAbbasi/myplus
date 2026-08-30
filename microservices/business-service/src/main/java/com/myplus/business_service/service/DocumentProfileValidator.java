package com.myplus.business_service.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * B2B Phase 3g — the server-side gate on an owner-designed Document Profile.
 *
 * <p><b>This is the safety boundary of the whole designer feature.</b> A profile decides what gets printed
 * on a document our customer hands to THEIR customer, so it is validated here and normalised before it is
 * ever stored — the browser is never trusted to have done it, whatever the designer screen enforces in the
 * UI. What comes back out of {@link #validateAndNormalise} contains nothing but keys, values and shapes
 * this build understands.
 *
 * <p>The rules:
 * <ul>
 *   <li><b>Every field key must be on a whitelist</b>, where the renderer binds it to a resolver. An owner
 *       controls presence, order, label, width and alignment — never code, never an expression language,
 *       never raw markup. That is what keeps this XSS-safe on a printed document, keeps built-in labels
 *       translatable across six locales, and keeps layouts upgradeable.</li>
 *   <li><b>Unknown keys are rejected, not dropped silently.</b> A designer that quietly discards a column
 *       teaches an owner their edit did not save.</li>
 *   <li><b>Sizes are bounded.</b> A label or a column count is owner-supplied text on a page we render;
 *       unbounded input is how a "layout" becomes a denial-of-service on the print path.</li>
 * </ul>
 *
 * <p><b>KEEP IN STEP WITH THE RENDERER.</b> The three sets below mirror {@code FIELD_WHITELIST} in
 * {@code src/main/resources/static/js/business/receipt.js}. They are deliberately duplicated across the
 * language boundary rather than shared: the client needs them to RENDER and the server needs them to
 * VALIDATE, and a server that trusts the client's list is not validating anything. Adding a field means
 * editing both, and each file carries a comment pointing at the other — the same arrangement used for the
 * pricing precedence rule mirrored between {@code PriceResolver} and the Price Rules screen.
 */
@Service
public class DocumentProfileValidator {

    /** Mirrors HEADER_FIELDS in receipt.js. */
    private static final Set<String> HEADER_FIELDS = Set.of(
            "invoiceNo", "dated", "datedTime", "time", "dueDate", "paymentMode", "storeName",
            "licenseNo", "licenseExpiry", "bookedBy",
            "customerName", "customerCode", "customerAddress", "customerMobile", "customerCity",
            "customerCnic", "customerLicenseNo", "customerLicenseExpiry",
            // Task #15 — return documents (credit note / debit note). These do NOT reuse invoiceNo even
            // though they resolve the same kind of string: the LABEL is the point, and a credit note printed
            // under an "Invoice #" heading is the confusion the note numbers exist to end.
            "creditNoteNo", "debitNoteNo", "referenceNo", "returnReason", "supplierName");

    /** Mirrors LINE_FIELDS in receipt.js. */
    private static final Set<String> LINE_FIELDS = Set.of(
            "lineNo", "itemCode", "itemName", "packing", "batchNo", "expiryDate",
            "quantity", "bonusQty", "tradePrice", "lineValue", "discountPct", "discount",
            "netTradePrice", "taxRate", "taxAmount", "lineTotal");

    /** Mirrors TOTAL_ROWS in receipt.js. */
    private static final Set<String> TOTAL_ROWS = Set.of(
            "itemCount", "qtyTotal", "bonusTotal", "valueTotal", "discountTotal", "subTotal", "taxTotal",
            "tradeDiscount", "shippingFee", "grandTotal", "amountInWords", "paidBy", "tendered", "change",
            "storeCredit", "storeCreditBalance", "due", "previousBalance", "currentBalance");

    private static final Set<String> PAPERS = Set.of("A4", "A5", "80mm", "58mm");
    private static final Set<String> ALIGNS = Set.of("left", "right", "center");
    private static final Set<String> TITLE_STYLES = Set.of("plain", "boxed");
    private static final Set<String> NUMBER_SYSTEMS = Set.of("indian", "international");

    // Bounds. Generous enough that no real layout hits them, tight enough that nothing pathological is stored.
    private static final int MAX_COLUMNS = 20;
    private static final int MAX_HEADER_GROUPS = 3;
    private static final int MAX_FIELDS_PER_GROUP = 8;
    private static final int MAX_TOTAL_ROWS = 24;
    private static final int MAX_LABEL = 40;
    private static final int MAX_FOOTER = 240;
    private static final int MAX_TITLE = 60;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Raised for anything an owner could plausibly have typed; the message is shown to them verbatim. */
    public static class InvalidProfileException extends RuntimeException {
        public InvalidProfileException(String message) { super(message); }
    }

    /**
     * Parse, validate and NORMALISE a profile.
     *
     * @return the canonical JSON to store — rebuilt field by field from what passed validation, never the
     *         caller's document echoed back. Anything the caller sent that is not modelled here is gone by
     *         construction, which is a stronger guarantee than trying to enumerate what to strip.
     */
    public String validateAndNormalise(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) throw new InvalidProfileException("The layout is empty.");
        JsonNode in;
        try {
            in = mapper.readTree(rawJson);
        } catch (Exception e) {
            throw new InvalidProfileException("The layout could not be read as JSON.");
        }
        if (!in.isObject()) throw new InvalidProfileException("The layout must be a JSON object.");

        ObjectNode out = mapper.createObjectNode();

        String paper = text(in, "paper", "80mm");
        if (!PAPERS.contains(paper))
            throw new InvalidProfileException("Unsupported paper size '" + paper + "'.");
        out.put("paper", paper);

        String numberSystem = text(in, "numberSystem", "indian");
        out.put("numberSystem", NUMBER_SYSTEMS.contains(numberSystem) ? numberSystem : "indian");

        out.put("showDrCr", in.path("showDrCr").asBoolean(false));

        String title = text(in, "title", null);
        if (title != null) {
            if (title.length() > MAX_TITLE)
                throw new InvalidProfileException("The document title is too long (max " + MAX_TITLE + ").");
            out.put("title", title);
        }

        // ------------------------------------------------------------------ header
        ObjectNode header = mapper.createObjectNode();
        JsonNode inHeader = in.path("header");
        String titleStyle = text(inHeader, "titleStyle", "plain");
        header.put("titleStyle", TITLE_STYLES.contains(titleStyle) ? titleStyle : "plain");
        header.put("showLogo", inHeader.path("showLogo").asBoolean(false));

        ArrayNode groups = mapper.createArrayNode();
        JsonNode inGroups = inHeader.path("columns");
        if (inGroups.isArray()) {
            if (inGroups.size() > MAX_HEADER_GROUPS)
                throw new InvalidProfileException("A document header may have at most "
                        + MAX_HEADER_GROUPS + " columns.");
            for (JsonNode group : inGroups) {
                if (!group.isArray()) throw new InvalidProfileException("Each header column must be a list.");
                if (group.size() > MAX_FIELDS_PER_GROUP)
                    throw new InvalidProfileException("A header column may have at most "
                            + MAX_FIELDS_PER_GROUP + " fields.");
                ArrayNode g = mapper.createArrayNode();
                for (JsonNode f : group) {
                    String key = f.asText("");
                    if (!HEADER_FIELDS.contains(key))
                        throw new InvalidProfileException("Unknown header field '" + key + "'.");
                    g.add(key);
                }
                groups.add(g);
            }
        }
        header.set("columns", groups);
        out.set("header", header);
        copyLabelMap(in, out, "headerLabels", HEADER_FIELDS);

        // ------------------------------------------------------------------ line columns
        JsonNode inLines = in.path("lines");
        if (!inLines.isArray() || inLines.isEmpty())
            throw new InvalidProfileException("A document needs at least one column.");
        if (inLines.size() > MAX_COLUMNS)
            throw new InvalidProfileException("A document may have at most " + MAX_COLUMNS + " columns.");

        ArrayNode lines = mapper.createArrayNode();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode c : inLines) {
            String key = c.path("key").asText("");
            if (!LINE_FIELDS.contains(key))
                throw new InvalidProfileException("Unknown column '" + key + "'.");
            // A repeated column is always a mistake, and it would print the same figure twice on an invoice.
            if (!seen.add(key))
                throw new InvalidProfileException("Column '" + key + "' appears more than once.");
            ObjectNode col = mapper.createObjectNode();
            col.put("key", key);
            String label = text(c, "label", null);
            if (label != null) {
                if (label.length() > MAX_LABEL)
                    throw new InvalidProfileException("The label for '" + key + "' is too long (max "
                            + MAX_LABEL + ").");
                col.put("label", label);           // owner text: stored verbatim, escaped at render
            }
            // Width is a percentage hint. Clamped rather than rejected: an out-of-range width is a slider
            // mishap, not an attack, and refusing to save someone's whole layout over it would be unkind.
            int width = c.path("width").asInt(0);
            if (width > 0) col.put("width", Math.min(Math.max(width, 1), 100));
            String align = text(c, "align", null);
            if (align != null && ALIGNS.contains(align)) col.put("align", align);
            lines.add(col);
        }
        out.set("lines", lines);

        // ------------------------------------------------------------------ totals
        ArrayNode totals = mapper.createArrayNode();
        JsonNode inTotals = in.path("totals");
        if (inTotals.isArray()) {
            if (inTotals.size() > MAX_TOTAL_ROWS)
                throw new InvalidProfileException("Too many summary rows (max " + MAX_TOTAL_ROWS + ").");
            java.util.Set<String> seenTotals = new java.util.HashSet<>();
            for (JsonNode tnode : inTotals) {
                String key = tnode.asText("");
                if (!TOTAL_ROWS.contains(key))
                    throw new InvalidProfileException("Unknown summary row '" + key + "'.");
                if (seenTotals.add(key)) totals.add(key);
            }
        }
        out.set("totals", totals);
        copyLabelMap(in, out, "totalLabels", TOTAL_ROWS);

        // ------------------------------------------------------------------ footer
        ObjectNode footer = mapper.createObjectNode();
        String footText = text(in.path("footer"), "text", "");
        if (footText != null && footText.length() > MAX_FOOTER)
            throw new InvalidProfileException("The footer text is too long (max " + MAX_FOOTER + ").");
        footer.put("text", footText == null ? "" : footText);
        footer.put("showSignature", in.path("footer").path("showSignature").asBoolean(false));
        out.set("footer", footer);

        return out.toString();
    }

    /** Owner-supplied label overrides, keyed by a whitelisted field. Same rules as a column label. */
    private void copyLabelMap(JsonNode in, ObjectNode out, String field, Set<String> allowed) {
        JsonNode map = in.path(field);
        if (!map.isObject() || map.isEmpty()) return;
        ObjectNode copy = mapper.createObjectNode();
        map.fields().forEachRemaining(e -> {
            if (!allowed.contains(e.getKey()))
                throw new InvalidProfileException("Unknown field '" + e.getKey() + "' in " + field + ".");
            String v = e.getValue().asText("");
            if (v.length() > MAX_LABEL)
                throw new InvalidProfileException("The label for '" + e.getKey() + "' is too long.");
            if (!v.isBlank()) copy.put(e.getKey(), v);
        });
        if (!copy.isEmpty()) out.set(field, copy);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return fallback;
        String s = v.asText("").trim();
        return s.isEmpty() ? fallback : s;
    }

    /** Exposed so the designer screen can offer exactly the fields this build can bind, and no others. */
    public java.util.Map<String, List<String>> whitelist() {
        return java.util.Map.of(
                "header", HEADER_FIELDS.stream().sorted().toList(),
                "line", LINE_FIELDS.stream().sorted().toList(),
                "totals", TOTAL_ROWS.stream().sorted().toList());
    }
}
