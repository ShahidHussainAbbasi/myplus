package com.myplus.common.settings;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * C1 — publishes every {@link Capability} onto the Configuration screen, as one switch each.
 *
 * <h3>Why it lives in the shared library</h3>
 * The same reason {@link LocaleSettingsCatalog} does: capabilities are not vertical-specific. A distributor
 * and a pharmacy draw from the same list and answer differently. A per-service copy would drift, and the
 * first thing to drift would be the defaults.
 *
 * <h3>Every capability defaults to ENABLED, and that is the migration strategy</h3>
 * On the deploy that introduces this, every tenant resolves every capability to true — so every screen and
 * every endpoint behaves exactly as it did the day before. The slice changes where an answer comes FROM, never
 * what the answer is. Anything else would take a screen away from a shop that was using it yesterday, which is
 * a support call and a lost sale rather than a tidy migration.
 *
 * <p>Turning one off is then an owner's deliberate act, visible on their own Configuration screen, and
 * reversible in a click.
 */
@Component
public class CapabilityCatalog implements SettingsCatalogProvider {

    /**
     * The group these appear under on the Configuration screen.
     *
     * <p>Deliberately its own group rather than scattered through "Sale entry" and "Stock": an owner deciding
     * what their business does is a different task from tuning how a form behaves, and mixing the two is how
     * a capability gets switched off by somebody adjusting a till.
     */
    private static final String GROUP = "What this business does";

    /**
     * C4 — the shape sits in its own group, ABOVE the capability switches.
     *
     * <p>Its own group because picking what kind of business you are is a different act from tuning one
     * behaviour: the shape seeds all twelve switches below it, and burying that among them invites an owner
     * to change it while looking for something else.
     */
    private static final String SHAPE_GROUP = "What kind of business this is";

    @Override
    public List<SettingEntry> entries() {
        List<SettingEntry> out = new ArrayList<>();

        // The shape. Default GENERAL, which presets every capability ON — so a tenant that never touches
        // this behaves exactly as it did before C4 existed. See Shape.GENERAL.
        List<SettingEntry.Option> shapes = new ArrayList<>();
        for (Shape s : Shape.values()) {
            shapes.add(new SettingEntry.Option(s.code(), s.label()));
        }
        out.add(SettingEntry.select(Shape.settingKey(), "Type of business",
                "Sets sensible defaults for the switches below. Anything you have already chosen yourself "
                        + "stays as you set it — changing this never overrides a decision you have made.",
                Shape.GENERAL.code(), SHAPE_GROUP, shapes));

        for (Capability c : Capability.values()) {
            // Default TRUE for every capability — see the class javadoc. If this ever becomes false for a
            // new capability, that is a decision to argue for in review, not a default to inherit quietly.
            out.add(SettingEntry.bool(c.settingKey(), c.label(), c.help(), true, GROUP));
        }
        return out;
    }
}
