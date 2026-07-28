/*
 * Responsive / app-shell validation across every dashboard × device.
 *
 * Guards the fixes for:
 *  - Left-gap bug: welfare/agriculture/education/appointment put id="nav-subheader" on the
 *    .app-sidebar rail, so the legacy #nav-subheader rule (position:sticky) overrode the fixed
 *    rail (ID beats class) → body.has-sidebar reserved 238px with nothing filling it. Rails are
 *    now id="app-sidebar" → the rail is position:fixed and flush-left with the content.
 *  - Bootstrap .container on welfare (fixed-width, auto-centred) → swapped for full-width #content.
 *  - Mobile CRUD modal was un-scrollable → shared /css/crud-modal.css scrolls the overlay so the
 *    Submit button is always reachable on a phone.
 *
 * Run (app must be up on :8080 with demo.* accounts seeded):
 *   npx cypress run  --spec cypress/e2e/ui/responsive.cy.js
 *   npx cypress open --e2e            (headed — pick responsive.cy.js)
 */

const SIDEBAR_W = 238;          // --sb-w in sidebar.css
const MOBILE_BP = 991;          // sidebar.css off-canvas breakpoint — the app's tablet edge

const MODULES = [
  { name: 'Business',    login: () => cy.loginAsBusiness(),    route: '/businessDashboard' },
  { name: 'Education',   login: () => cy.loginAsEducation(),   route: '/educationDashboard' },
  { name: 'Welfare',     login: () => cy.loginAsWelfare(),     route: '/welfareDashboard' },
  { name: 'Agriculture', login: () => cy.loginAsAgriculture(), route: '/agricultureDashboard' },
  { name: 'Appointment', login: () => cy.loginAsAppointment(), route: '/appointmentDashboard' },
];

const DEVICES = [
  { label: 'iPhone 13 (390×844)',  w: 390,  h: 844,  mobile: true  },
  { label: 'iPad (768×1024)',      w: 768,  h: 1024, mobile: true  },   // ≤991 ⇒ off-canvas
  // 960 sat in the old 900–991 dead-zone: drawer nav had NOT kicked in (rail still took 238px)
  // while col-sm-* was already in its cramped band, so the content had neither the rail's room
  // nor the drawer's full width. Locks the breakpoint alignment to one edge.
  { label: 'Tablet landscape (960×600)', w: 960, h: 600, mobile: true },
  { label: 'Laptop (1280×800)',    w: 1280, h: 800,  mobile: false },
  { label: 'Desktop (1680×1050)',  w: 1680, h: 1050, mobile: false },
];

// The whole page must never scroll horizontally (a couple px of rounding tolerance).
function assertNoHorizontalOverflow() {
  cy.document().then((doc) => {
    const el = doc.documentElement;
    expect(el.scrollWidth, 'document scrollWidth ≤ viewport width (no sideways scroll)')
      .to.be.at.most(el.clientWidth + 2);
  });
}

describe('Responsive app-shell across dashboards & devices', () => {
  MODULES.forEach((mod) => {
    describe(mod.name, () => {
      beforeEach(() => {
        // Cypress testIsolation clears the session between tests — re-login every test.
        mod.login();
      });

      DEVICES.forEach((dev) => {
        it(`${dev.label}: no overflow, correct sidebar layout`, () => {
          cy.viewport(dev.w, dev.h);
          cy.visit(mod.route);
          cy.get('.app-sidebar', { timeout: 10000 }).should('exist');

          assertNoHorizontalOverflow();

          cy.get('.app-sidebar').then(($sb) => {
            const style = getComputedStyle($sb[0]);
            const rect = $sb[0].getBoundingClientRect();

            if (dev.w > MOBILE_BP) {
              // ── Desktop: the rail must be a FIXED left column (this is the regression the fix
              //    addressed — it was resolving to 'sticky' via the #nav-subheader override).
              expect(style.position, `${mod.name} rail must be position:fixed on desktop`).to.eq('fixed');
              expect(Math.round(rect.left), 'rail flush to the left edge').to.eq(0);
              expect(rect.width, 'rail ≈ 238px wide').to.be.closeTo(SIDEBAR_W, 4);

              // No empty band on the left: the reserved space equals the rail width (rail.right ≈ 238),
              // so content begins immediately after the rail rather than after a gap.
              cy.get('body').then(($b) => {
                const padL = parseFloat(getComputedStyle($b[0]).paddingLeft) || 0;
                expect(padL, 'body reserves exactly the rail width').to.be.closeTo(SIDEBAR_W, 4);
                expect(rect.right, 'rail ends where content starts — no left gap').to.be.closeTo(padL, 4);
              });
            } else {
              // ── Mobile/tablet: rail is off-canvas (translated left) and the hamburger drives it.
              expect(rect.right, `${mod.name} rail off-canvas until opened`).to.be.at.most(1);
              cy.get('#sidebar-mobile-btn').should('be.visible');
              cy.get('body').then(($b) => {
                const padL = parseFloat(getComputedStyle($b[0]).paddingLeft) || 0;
                expect(padL, 'no reserved rail space on mobile').to.be.lessThan(4);
              });
            }
          });
        });
      });

      it('mobile: hamburger opens & closes the drawer', () => {
        cy.viewport(390, 844);
        cy.visit(mod.route);
        cy.get('#sidebar-mobile-btn').should('be.visible').click();
        cy.get('body').should('have.class', 'sidebar-open');
        cy.get('.app-sidebar').then(($sb) => {
          expect(Math.round($sb[0].getBoundingClientRect().left), 'drawer slides fully into view').to.eq(0);
        });
        cy.get('.sidebar-backdrop').click({ force: true });
        cy.get('body').should('not.have.class', 'sidebar-open');
      });
    });
  });

  // ── Welfare & agriculture were converted from always-open inline forms to the shared modal
  //    pattern (New → modal, row-click → edit modal). Assert the modal shells + New buttons rendered
  //    (server-side Thymeleaf ok) and open cleanly.
  describe('Welfare — inline forms converted to modals', () => {
    beforeEach(() => cy.loginAsWelfare());
    it('Donator & Donation modals render and open', () => {
      // Real nav path (same as business): select the section → main.js reveals it → open its modal.
      cy.openSection('DonatorDiv', '/welfareDashboard');
      cy.get('#DonationModal').should('exist');
      cy.get('#newDonator').click();
      cy.get('#DonatorModal').should('have.class', 'open');
      cy.get('#addDonator').should('be.visible');
    });
  });

  describe('Agriculture — inline forms converted to modals', () => {
    beforeEach(() => cy.loginAsAgriculture());
    it('Land / Expense / Income modals render and open', () => {
      cy.openSection('landDiv', '/agricultureDashboard');
      cy.get('#AgricultureExpenseModal').should('exist');
      cy.get('#AgricultureIncomeModal').should('exist');
      cy.get('#newLand').click();
      cy.get('#LandModal').should('have.class', 'open');
      cy.get('#addLand').should('be.visible');
    });
  });

  // ── The original report: could not register a customer on an iPhone 13 because the modal
  //    Submit button was clipped below the fold. The overlay must now scroll so it's reachable.
  describe('Business — customer registration modal is usable on a phone', () => {
    beforeEach(() => cy.loginAsBusiness());

    it('iPhone 13: New Customer modal Submit is reachable', () => {
      cy.viewport(390, 844);
      cy.visit('/businessDashboard');
      cy.openSection('CustomerDiv');
      cy.get('#newCustomer').should('be.visible').click();
      cy.get('#CustomerModal').should('have.class', 'open');
      // The overlay scrolls; the Submit button must be reachable and clickable.
      cy.get('#addCustomer').scrollIntoView().should('be.visible');
      assertNoHorizontalOverflow();
    });
  });

  /* ══════════════════════════════════════════════════════════════════════════
   * Wide grids must SCROLL, not clip.
   *
   * theme.css hides body overflow-x below 767px while only .dataTables_wrapper and
   * .table-responsive carried a scroller of their own. Only 3 of ~37 dashboard grids
   * are DataTables and .table-responsive appeared in no template, so every other
   * table had its right-hand columns cut off by the body rule and unreachable — not
   * merely awkward to read, but impossible to see at all.
   *
   * /js/common/responsive-tables.js now wraps each grid in .table-scroll.
   * ════════════════════════════════════════════════════════════════════════ */

  // Proves the columns are genuinely REACHABLE, which is what the clip took away:
  // the grid sits in a scroller, and scrolling it to the end brings the last
  // header cell inside the viewport.
  function assertGridFullyReachable(tableSelector) {
    cy.get(tableSelector).should('exist').then(($t) => {
      const table = $t[0];
      const wrap = table.parentElement;

      expect(wrap.classList.contains('table-scroll'), `${tableSelector} sits in a .table-scroll`)
        .to.be.true;

      const maxScroll = wrap.scrollWidth - wrap.clientWidth;
      if (maxScroll <= 1) return;    // fits at this width — nothing to reach

      wrap.scrollLeft = maxScroll;
      expect(wrap.scrollLeft, 'the wrapper actually scrolls (it is not clipped)')
        .to.be.closeTo(maxScroll, 2);

      const lastCell = table.querySelector('thead th:last-child');
      if (!lastCell) return;
      const cell = lastCell.getBoundingClientRect();
      expect(cell.right, 'last column is on-screen once scrolled to the end')
        .to.be.at.most(window.innerWidth + 2);
      expect(cell.width, 'last column has real width (not squashed to nothing)')
        .to.be.greaterThan(0);
    });
  }

  describe('Education — wide grids scroll instead of clipping', () => {
    beforeEach(() => cy.loginAsEducation());

    // 13 columns — the worst case in the app, and fully unreachable on a phone before this.
    it('iPhone 13: Fee Report (13 columns) is scrollable end-to-end', () => {
      cy.viewport(390, 844);
      cy.visit('/educationDashboard');
      cy.get('#feeType').select('FRDiv', { force: true });
      cy.get('#FRDiv').should('be.visible');

      assertGridFullyReachable('#frTable');
      assertNoHorizontalOverflow();      // the page itself still must not move sideways
    });

    it('iPad: Students grid is scrollable and the page does not shift', () => {
      cy.viewport(768, 1024);
      cy.openSection('StudentDiv', '/educationDashboard');
      assertGridFullyReachable('#tableStudent');
      assertNoHorizontalOverflow();
    });
  });

  describe('Pharmacy — clinical grids scroll instead of clipping', () => {
    beforeEach(() => cy.loginAsPharma());

    it('iPhone 13: prescription + controlled-substance registers are reachable', () => {
      cy.viewport(390, 844);
      cy.visit('/businessDashboard');

      // Pharmacy screens are vertical-gated (data-vertical-only="PHARMA") and revealed by
      // pharma.js rather than a nav select, so drive the same entry point the sidebar uses.
      cy.window().then((w) => w.showPrescriptions());
      cy.get('#PrescriptionDiv').should('be.visible');
      assertGridFullyReachable('#tablePrescription');
      assertNoHorizontalOverflow();

      cy.window().then((w) => w.showPharmAlerts());
      cy.get('#PharmAlertsDiv').should('be.visible');
      assertGridFullyReachable('#tableControlled');
      assertNoHorizontalOverflow();
    });
  });

  /* ══════════════════════════════════════════════════════════════════════════
   * Tablet band (768–991px): no field may collapse below a usable width.
   *
   * Every dashboard form is .form-horizontal with col-sm-* cells sized for a 1400px
   * desktop. At 768px a col-sm-1 cell is ~64px, so the prescription intake's Qty /
   * Freq / Duration inputs existed but could not be typed into. /css/responsive.css
   * turns the row into a wrapping flex line with a 170px floor.
   * ════════════════════════════════════════════════════════════════════════ */

  const USABLE_MIN = 120;   // 170px cell − Bootstrap's 15px gutters, with tolerance

  describe('Pharmacy — prescription intake is usable on a tablet', () => {
    beforeEach(() => cy.loginAsPharma());

    it('iPad: the col-sm-1 Qty / Freq / Duration inputs are wide enough to use', () => {
      cy.viewport(768, 1024);
      cy.visit('/businessDashboard');
      cy.window().then((w) => w.showPrescriptions());
      cy.get('#PrescriptionDiv').should('be.visible');

      ['#rxQty', '#rxFreq', '#rxDuration', '#rxDosage', '#rxMedicine'].forEach((sel) => {
        cy.get(sel).then(($el) => {
          expect($el[0].getBoundingClientRect().width, `${sel} is usably wide on a tablet`)
            .to.be.at.least(USABLE_MIN);
        });
      });

      assertNoHorizontalOverflow();
    });

    it('iPad: the Clinical & Safety interaction row does not cram four selects onto one line', () => {
      cy.viewport(768, 1024);
      cy.visit('/businessDashboard');
      cy.window().then((w) => w.showClinical());
      cy.get('#ClinicalDiv').should('be.visible');

      ['#clInterA', '#clInterB', '#clSeverity'].forEach((sel) => {
        cy.get(sel).then(($el) => {
          expect($el[0].getBoundingClientRect().width, `${sel} is usably wide on a tablet`)
            .to.be.at.least(USABLE_MIN);
        });
      });

      assertNoHorizontalOverflow();
    });
  });

  describe('Education — Team toolbar wraps instead of clipping its Add button', () => {
    beforeEach(() => cy.loginAsEducation());

    it('iPad: the toolbar Add button keeps its full label', () => {
      cy.viewport(768, 1024);
      cy.visit('/educationDashboard');
      cy.window().then((w) => w.showTeam());
      cy.get('#TeamDiv').should('be.visible');

      // The button was in a col-sm-1 (~64px) — its label overflowed the cell.
      cy.get('#addTeamUser').then(($b) => {
        const btn = $b[0];
        expect(btn.scrollWidth, 'Add button label is not clipped')
          .to.be.at.most(btn.clientWidth + 2);
      });

      cy.get('#teamEmail').then(($el) => {
        expect($el[0].getBoundingClientRect().width, 'email field stays usable')
          .to.be.at.least(USABLE_MIN);
      });

      assertNoHorizontalOverflow();
    });
  });
});
