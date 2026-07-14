/*
 * SaaS app-shell sidebar behaviour (shared). Desktop: collapse to an icon rail (persisted).
 * Mobile (<=900px): off-canvas drawer opened by the hamburger. Reuses the existing snav accordion
 * (snavToggle) for group expand/collapse — this only adds the rail collapse + mobile drawer + active state.
 */
(function () {
    'use strict';

    function persist(collapsed) { try { localStorage.setItem('mp_sidebar_collapsed', collapsed ? '1' : '0'); } catch (e) {} }

    // Desktop: toggle the icon-rail. Mobile: open/close the drawer (the same button does both per breakpoint).
    window.toggleSidebar = function () {
        if (window.matchMedia && window.matchMedia('(max-width:900px)').matches) {
            document.body.classList.toggle('sidebar-open');
            return;
        }
        persist(document.body.classList.toggle('sidebar-collapsed'));
    };
    window.openSidebar = function () { document.body.classList.add('sidebar-open'); };
    window.closeSidebar = function () { document.body.classList.remove('sidebar-open'); };

    // ── "Remember last selection" (shared by every dashboard) ─────────────────────────────────────
    // The last-picked nav item stays visibly highlighted across the whole UI, and is restored after a
    // reload, so the user always sees where they are and never has to re-navigate. Works for BOTH nav
    // shapes: the snav accordion (business/education/appointment) and the flat sb-link rail
    // (welfare/agriculture). Keyed per page so dashboards don't clobber each other.
    var NAV_KEY = 'mp_nav_active:' + location.pathname;
    function navKeyOf(a) { return (a.getAttribute('onclick') || a.textContent || '').replace(/\s+/g, ' ').trim(); }

    // Highlight a nav link + (for the accordion) its parent group button, and KEEP that group's submenu
    // open so the selection stays visible. Clears any previous highlight/open state first. We manage
    // .snav-open explicitly (the reliable show mechanism used by both CSS files) rather than relying on
    // the fragile :has(.snav-active) rule — that was why snavGo items (which remove .snav-open) collapsed
    // while Finance/showX items (which don't) stayed visible. Optionally persists the choice.
    // The accordion group holding the current selection, if any. Written the long way rather than with
    // :has(), which isn't safe on the browser floor this app targets.
    function activeGroup(sb) {
        var marker = sb.querySelector('.snav-menu a.active, .snav-btn.snav-active');
        return (marker && marker.closest) ? marker.closest('.snav-dd') : null;
    }

    function markActive(sb, a, save) {
        sb.querySelectorAll('.snav-menu a.active, .sb-link.active').forEach(function (el) { el.classList.remove('active'); });
        sb.querySelectorAll('.snav-btn.snav-active').forEach(function (el) { el.classList.remove('snav-active'); });
        sb.querySelectorAll('.snav-dd.snav-open').forEach(function (el) { el.classList.remove('snav-open'); });
        if (!a) return;
        a.classList.add('active');
        var dd = a.closest ? a.closest('.snav-dd') : null;
        if (dd) {
            dd.classList.add('snav-open');                                   // keep the picked group's menu open
            var btn = dd.querySelector(':scope > .snav-btn');
            if (btn) btn.classList.add('snav-active');                        // + highlight its group button
        }
        if (save) { try { localStorage.setItem(NAV_KEY, navKeyOf(a)); } catch (e) {} }
    }

    // For the select-nav dashboards (welfare/agriculture): a rail link sets the (now off-screen) section
    // <select> and fires its change handler — the same mechanism the old dropdown used. Marks itself active.
    window.sbNav = function (selectId, value, el) {
        var s = document.getElementById(selectId);
        if (s) {
            s.value = value;
            if (window.jQuery) window.jQuery(s).trigger('change'); else s.dispatchEvent(new Event('change'));
        }
        var sb = document.querySelector('.app-sidebar');
        if (sb) markActive(sb, el, true);
        window.closeSidebar();
    };

    document.addEventListener('DOMContentLoaded', function () {
        // restore the desktop collapsed state
        try { if (localStorage.getItem('mp_sidebar_collapsed') === '1') document.body.classList.add('sidebar-collapsed'); } catch (e) {}

        var sb = document.querySelector('.app-sidebar');
        if (!sb) return;

        // Highlight (+ persist) the picked accordion item; its own onclick still runs the navigation.
        sb.addEventListener('click', function (e) {
            var a = (e.target && e.target.closest) ? e.target.closest('.snav-menu a') : null;
            if (!a) return;
            markActive(sb, a, true);
            window.closeSidebar();   // close the mobile drawer after navigating
        });

        // Clicking away closes any group the user merely PEEKED at — but never the one holding the current
        // selection. Each dashboard used to do this itself and closed *every* group, which silently undid the
        // markActive() above: the moment you clicked into the form you were working on, the sidebar forgot
        // where you were. The selection now survives until you pick something else.
        document.addEventListener('click', function (e) {
            var insideMenu = e.target && e.target.closest && e.target.closest('.snav-dd');
            if (insideMenu) return;
            var selected = activeGroup(sb);
            sb.querySelectorAll('.snav-dd.snav-open').forEach(function (dd) {
                if (dd !== selected) dd.classList.remove('snav-open');
            });
            // ...and put the selection back if a "peek" at another group collapsed it (snavToggle closes ALL
            // groups before opening the one you clicked). Clicking away should leave you exactly where you were.
            if (selected) selected.classList.add('snav-open');
        });

        // Restore the last selection after a reload: re-highlight it AND replay its navigation so the
        // user lands back on their last view (not the default overview) — the whole point of remembering.
        // Deferred so it runs AFTER each dashboard's own init (which shows the default view), then overrides it.
        setTimeout(function () {
            try {
                var saved = localStorage.getItem(NAV_KEY);
                if (!saved) return;
                var links = sb.querySelectorAll('.snav-menu a, .sb-link');
                for (var i = 0; i < links.length; i++) {
                    if (navKeyOf(links[i]) === saved) {
                        var a = links[i];
                        var oc = a.getAttribute('onclick');
                        if (oc) { try { new Function(oc).call(a); } catch (e) {} }   // replay the navigation first
                        markActive(sb, a, false);                                    // then highlight (matches a real click's order)
                        break;
                    }
                }
            } catch (e) {}
        }, 0);
    });
})();
