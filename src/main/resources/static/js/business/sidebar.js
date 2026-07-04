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

    // For the select-nav dashboards (welfare/agriculture): a rail link sets the (now off-screen) section
    // <select> and fires its change handler — the same mechanism the old dropdown used. Marks itself active.
    window.sbNav = function (selectId, value, el) {
        var s = document.getElementById(selectId);
        if (s) {
            s.value = value;
            if (window.jQuery) window.jQuery(s).trigger('change'); else s.dispatchEvent(new Event('change'));
        }
        var sb = document.querySelector('.app-sidebar');
        if (sb) { sb.querySelectorAll('.sb-link.active').forEach(function (a) { a.classList.remove('active'); }); }
        if (el) el.classList.add('active');
        window.closeSidebar();
    };

    document.addEventListener('DOMContentLoaded', function () {
        // restore the desktop collapsed state
        try { if (localStorage.getItem('mp_sidebar_collapsed') === '1') document.body.classList.add('sidebar-collapsed'); } catch (e) {}

        var sb = document.querySelector('.app-sidebar');
        if (!sb) return;
        sb.addEventListener('click', function (e) {
            var a = (e.target && e.target.closest) ? e.target.closest('.snav-menu a') : null;
            if (!a) return;
            sb.querySelectorAll('.snav-menu a.active').forEach(function (el) { el.classList.remove('active'); });
            a.classList.add('active');
            window.closeSidebar();   // close the mobile drawer after navigating
        });
    });
})();
