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
const MOBILE_BP = 900;          // sidebar.css off-canvas breakpoint

const MODULES = [
  { name: 'Business',    login: () => cy.loginAsBusiness(),    route: '/businessDashboard' },
  { name: 'Education',   login: () => cy.loginAsEducation(),   route: '/educationDashboard' },
  { name: 'Welfare',     login: () => cy.loginAsWelfare(),     route: '/welfareDashboard' },
  { name: 'Agriculture', login: () => cy.loginAsAgriculture(), route: '/agricultureDashboard' },
  { name: 'Appointment', login: () => cy.loginAsAppointment(), route: '/appointmentDashboard' },
];

const DEVICES = [
  { label: 'iPhone 13 (390×844)',  w: 390,  h: 844,  mobile: true  },
  { label: 'iPad (768×1024)',      w: 768,  h: 1024, mobile: true  },   // ≤900 ⇒ off-canvas
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
});
