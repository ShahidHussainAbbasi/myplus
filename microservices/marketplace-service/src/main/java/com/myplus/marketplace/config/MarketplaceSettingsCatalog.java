package com.myplus.marketplace.config;

import java.util.List;

import org.springframework.stereotype.Component;

import com.myplus.common.settings.SettingEntry;
import com.myplus.common.settings.SettingsCatalogProvider;

/**
 * OMS O3 — the order/checkout policies a store owner may configure. marketplace-service's FIRST contribution to
 * the shared settings catalog; before this it had no {@code common-settings} consumer at all.
 *
 * <p>Every entry here replaces something that was a literal in code, so every store on the platform behaved
 * identically and could only be changed by a redeploy for all tenants at once. The defaults reproduce exactly
 * what those literals were, so an org that never opens the Configuration screen sees no change in behaviour.
 *
 * <p>Adding an entry is all it takes to surface a toggle — the screen renders from this catalog and has no
 * knowledge of individual settings.
 */
@Component
public class MarketplaceSettingsCatalog implements SettingsCatalogProvider {

    public static final String STANDARD_FEE = "order.shipping.standardFee";
    public static final String EXPRESS_FEE = "order.shipping.expressFee";
    public static final String FREE_OVER = "order.shipping.freeOverAmount";
    public static final String COD_ENABLED = "order.payment.codEnabled";

    /** OMS O5c — may this shop accept an order it cannot fill today? */
    public static final String BACKORDER_ALLOWED = "order.backorder.allowed";
    /** OMS O5c — how far ahead a backordered order is promised. */
    public static final String BACKORDER_PROMISE_DAYS = "order.backorder.promiseDays";
    public static final int DEFAULT_PROMISE_DAYS = 7;
    /** OMS O5c — accept an order when NOTHING is available, not just when part is? */
    public static final String BACKORDER_FULL_SHORTFALL = "order.backorder.acceptFullShortfall";

    // ── OMS O5d packing — withdrawn 2026-08-10, RESTORED by O7 D3 2026-08-13 with the workbench. ────────
    /** OMS O5d — must a packer SCAN what goes in the box, or may they type it? */
    public static final String PACK_SCAN_REQUIRED = "order.pack.scanRequired";
    /** OMS O5d — once everything outstanding is packed, dispatch automatically or wait for a human? */
    public static final String PACK_AUTO_CONFIRM = "order.pack.autoConfirm";

    /**
     * OMS O8 — collapse pack and dispatch into the approval, for a shop that does not run a warehouse step.
     *
     * <p>A small distributor loads the van from the same room the order was approved in. Making them walk a
     * pick list and record a parcel for goods that never sat on a shelf is ceremony, and ceremony gets skipped
     * — which in this system means the order is never invoiced at all, because the SHIPMENT is what raises a
     * field order's invoice.
     *
     * <p>So the step is not removed, it is performed: approving records a full shipment through the ordinary
     * {@code ShipmentService}, which stays the only writer of shipments and the only trigger of a dispatch
     * invoice. Everything downstream — the round sheet, the challan, delivery keying, the short-delivery credit
     * note, driver settlement — is untouched, because none of them can tell who recorded the parcel.
     */
    public static final String AUTO_DISPATCH_ON_APPROVAL = "order.flow.autoDispatchOnApproval";

    /**
     * OMS O7 D1 — must a booked order be reviewed before it can be picked?
     *
     * <p>The approval gate was hardcoded: every order a rep books stops at {@code PENDING_APPROVAL} and waits
     * for someone with authority. That is the right default, and it is the control the pre-sales model rests
     * on — but it assumes there are TWO people. In a distributor where one person in the back office books,
     * reviews and converts, the gate segregates nothing: it is the same human clicking twice. A control that
     * cannot fail is not a control, it is friction, and friction teaches people to work around the system.
     *
     * <p>Off, the order enters at {@code NEW} and goes straight to picking. <b>The record survives either
     * way</b> — {@code bookedByName} is still stamped, the timeline still logs the transition, the audit
     * service still records who did it. What is lost is the second pair of eyes, which a one-person office
     * never had; what is kept is the ability to answer "who did this".
     *
     * <p>Same shape as the quote discount threshold, which took the same view: no gate configured, no gate.
     */
    public static final String BOOKING_REQUIRE_APPROVAL = "order.booking.requireApproval";

    private static final String GROUP = "Online orders";
    /** Its own section: packing is a different job, done by a different person, from taking an order. */
    private static final String PACK_GROUP = "Packing & dispatch";
    /** Field sales: booking and its review are a different job again from either of the above. */
    private static final String FIELD_GROUP = "Field orders";

    /**
     * <h3>The two packing settings, WITHDRAWN 2026-08-10 and RESTORED 2026-08-13 (O7 D3)</h3>
     *
     * They were pulled because neither could be honoured: {@code autoConfirm} was read nowhere, and
     * {@code scanRequired} was worse — enforced, but unsatisfiable, because the only UI that dispatched never
     * sent {@code verified}, so switching it on refused every dispatch. That is C1's rule applied honestly:
     * an owner must not be offered a choice the product cannot keep.
     *
     * <p>D3 built the workbench that makes both real — it scans items into a parcel, marks those lines
     * verified, and can dispatch the moment the last outstanding unit is packed. So they come back, together,
     * with the thing that gives them meaning. <b>Both still default OFF</b>: not every shop owns a scanner, and
     * a workflow that assumes equipment a merchant does not have is one they cannot use at all.
     *
     * <h3>Historic note — why the withdrawal is worth remembering</h3>
     *
     * O5d shipped its backend half and none of its packer-facing half, and both settings were left rendering on
     * the owner's screen. That breaks <b>C1</b> — <i>a toggle that changes nothing is worse than no toggle</i> —
     * in the two distinct ways C1 exists to catch:
     *
     * <ul>
     *   <li>{@code order.pack.autoConfirm} is read <b>nowhere in main/</b>. Inert, exactly as
     *       {@code pharmacy.interaction.blockSevere} was.</li>
     *   <li>{@code order.pack.scanRequired} is worse than inert — it is a <b>trap</b>. It IS enforced (in
     *       {@code ShipmentService}, the only writer), but the only UI that dispatches, {@code submitShipment},
     *       posts {@code {orderItemId, quantity}} and never {@code verified}. So switching it on refuses
     *       <b>every</b> dispatch, telling the packer to scan into a workbench that was never built.</li>
     * </ul>
     *
     * Withdrawing beats fixing the wording: an owner cannot be offered a choice the product cannot honour. They
     * come back — with their enforcement, their gate cases and their help text — in the slice that builds the
     * workbench, which is the only thing that makes either of them mean anything. The constants, the
     * {@code verified} column (V17) and {@code ShipmentLine.verified} all stay: the column is applied and
     * honest (pre-workbench parcels WERE typed), and it is what the workbench will record into.
     */
    @Override
    public List<SettingEntry> entries() {
        return List.of(
                SettingEntry.money(STANDARD_FEE,
                        "Standard delivery fee",
                        "Charged on a storefront order with Standard delivery. Was fixed at 5.00 for every store "
                                + "on the platform until this became configurable.",
                        "5.00", GROUP),
                SettingEntry.money(EXPRESS_FEE,
                        "Express delivery fee",
                        "Charged on a storefront order with Express delivery.",
                        "15.00", GROUP),
                SettingEntry.money(FREE_OVER,
                        "Free delivery over",
                        "Orders at or above this value ship free, whichever method the shopper picks. "
                                + "Leave at 0 to always charge the fees above.",
                        "0", GROUP),
                SettingEntry.bool(COD_ENABLED,
                        "Accept cash on delivery",
                        "On (default): shoppers may choose Cash on Delivery. Off: only card checkout is accepted "
                                + "and a COD order is refused by the server, not merely hidden.",
                        true, GROUP),
                // OMS O5c. Default OFF: accepting orders you cannot fill is a commercial decision with real
                // consequences (a customer waiting on goods that may never arrive), not a sensible default.
                // Off reproduces exactly today's behaviour — the checkout is refused.
                SettingEntry.bool(BACKORDER_ALLOWED,
                        "Accept orders for out-of-stock items (backorders)",
                        "Off (default): a checkout is refused when stock is short, as now. On: the order is "
                                + "accepted, the part you can fill is invoiced and dispatched, and the rest is "
                                + "recorded as owed until stock arrives. The shopper is told before they commit.",
                        false, GROUP),
                // The order in which these two read matters: this one only applies once backorders are on.
                SettingEntry.bool(BACKORDER_FULL_SHORTFALL,
                        "Accept orders when nothing is in stock",
                        "On (default): an order can be accepted even when none of it can be filled today — no "
                                + "invoice is raised until goods are dispatched, so you only ever bill for what "
                                + "you deliver. Off: only orders you can PARTLY fill are accepted, and a "
                                + "completely out-of-stock item is refused at checkout.",
                        true, GROUP),
                SettingEntry.intOf(BACKORDER_PROMISE_DAYS,
                        "Promise backordered items within (days)",
                        "How far ahead to promise the outstanding part of a backordered order. Shown to the "
                                + "shopper at checkout and used to flag late orders in the back office.",
                        DEFAULT_PROMISE_DAYS, GROUP),

                // ── OMS O7 D3 — packing. Restored with the workbench that makes them honourable. ──────
                // Both OFF by default, and for the same reason: a shop with no scanner, or one that wants a
                // human to look in the box before it leaves, must keep working exactly as it does today.
                SettingEntry.bool(AUTO_DISPATCH_ON_APPROVAL,
                        "Dispatch as soon as an order is approved",
                        "Off (default): an approved order waits to be picked and packed. On: approving it also "
                                + "records the full quantity as dispatched and raises the invoice, so the round "
                                + "is book → approve → print → deliver with no warehouse step. "
                                + "Short deliveries are still handled where they are discovered — keying "
                                + "the delivery raises the credit note. Cannot be used with scan-to-pack, and an "
                                + "order whose stock cannot be set aside is left to be packed by hand rather "
                                + "than dispatched short.",
                        false, PACK_GROUP),
                SettingEntry.bool(PACK_SCAN_REQUIRED,
                        "Require items to be scanned when packing",
                        "Off (default): a packer may type the quantities, as now. On: each item must be scanned "
                                + "into the parcel on the Pack screen, so packing the wrong product is caught "
                                + "at the shelf rather than by the customer. Lines entered by hand are always "
                                + "recorded as unverified either way.",
                        false, PACK_GROUP),
                SettingEntry.bool(PACK_AUTO_CONFIRM,
                        "Dispatch automatically once everything is packed",
                        "Off (default): the packer confirms the parcel, adds the carrier and tracking number, "
                                + "and then it is dispatched. On: the shipment is recorded as soon as the last "
                                + "outstanding item is scanned — faster for a high-volume shop, but nobody "
                                + "gets a final look before it goes.",
                        false, PACK_GROUP),

                // ── OMS O7 D1 — the approval gate, now a choice. ─────────────────────────────────────
                // ON by default, which is exactly today's hardcoded behaviour: an org that never opens this
                // screen sees no change. Off is for the one-person back office, where the gate segregates
                // nothing.
                SettingEntry.bool(BOOKING_REQUIRE_APPROVAL,
                        "Review booked orders before picking",
                        "On (default): an order booked by a rep waits at Pending approval until an owner or "
                                + "admin confirms it, and the rep cannot release their own order. Off: booked "
                                + "orders go straight to picking — for a back office where the same person "
                                + "books and converts, so there is no second pair of eyes to wait for. Who "
                                + "booked each order is recorded either way.",
                        true, FIELD_GROUP));
    }
}
