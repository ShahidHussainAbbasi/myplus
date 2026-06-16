/**
 * Signup — SaaS self-service tenant provisioning (slice 32).
 *
 * Verifies that registration now (a) carries an Organization name, (b) on success provisions the owner's
 * tenant, and (c) enforces validation (required org name, duplicate email) and the email-verification gate.
 *
 * Assumes captcha is OFF for the test profile (app.captcha.enabled=false) — same as the rest of the suite;
 * the controller's captcha check is then a no-op. Run headed:  npx cypress run --headed --browser chrome
 *   --spec cypress/e2e/auth/signup.cy.js
 */

const PW = 'Test@2025!'

// A field-by-field form fill for the live /registration.html (jQuery $.post to /user/registration).
// userType is now a required selector (the chosen domain sets the role), so always pick one.
function fillForm({ first = 'Test', last = 'Owner', org, email, pw = PW, userType = 'BUSINESS' } = {}) {
  cy.get('input[name="firstName"]').clear().type(first)
  cy.get('input[name="lastName"]').clear().type(last)
  if (org !== undefined) cy.get('input[name="organizationName"]').clear().type(org)
  cy.get('select[name="userType"]').select(userType)
  cy.get('input[name="email"]').clear().type(email)
  cy.get('#password').clear().type(pw)
  cy.get('#matchPassword').clear().type(pw)
}

describe('Signup — Organization name field (slice 32)', () => {
  beforeEach(() => {
    cy.clearCookies()
    cy.visit('/registration.html', { failOnStatusCode: false })
  })

  it('renders the Organization name input', () => {
    cy.get('input[name="organizationName"]').should('exist').and('be.visible')
  })

  it('keeps the other required fields', () => {
    cy.get('input[name="firstName"]').should('exist')
    cy.get('input[name="lastName"]').should('exist')
    cy.get('input[name="email"]').should('exist')
    cy.get('#password').should('exist')
  })
})

describe('Signup — happy path provisions a tenant', () => {
  it('POST /user/registration with an org name succeeds', () => {
    const email = `signup_${Date.now()}@example.com`
    cy.intercept('POST', '/user/registration').as('reg')
    cy.visit('/registration.html', { failOnStatusCode: false })
    fillForm({ org: 'Beacon Rise Grammar School', email })
    cy.get('button[type="submit"]').click({ force: true })
    // Assert the intercepted response (not redirect timing): GenericResponse("success") sets status.
    cy.wait('@reg').then(({ response }) => {
      expect(response.statusCode).to.eq(200)
      expect(response.body).to.have.property('status', 'success')
      expect(response.body.error).to.be.null
    })
  })

  // Regression: the success branch checked data.message (always null) instead of data.status, so a
  // successful registration gave the user NO feedback. It must now redirect to the success page.
  it('on success redirects to the success page (status-based success check)', () => {
    const email = `signup_ui_${Date.now()}@example.com`
    cy.visit('/registration.html', { failOnStatusCode: false })
    fillForm({ org: 'Beacon Rise Grammar School', email })
    cy.get('button[type="submit"]').click({ force: true })
    // The fixed success-check (status === 'success') drives the redirect to the proper success page.
    cy.url({ timeout: 15000 }).should('include', 'successRegister')
    cy.contains('Registration successful').should('be.visible')
  })

  // The global AJAX overlay (/js/common/ajax-overlay.js) is injected app-wide from a single file.
  it('exposes the single global AJAX overlay element on the page', () => {
    cy.visit('/registration.html', { failOnStatusCode: false })
    // It self-injects on load; assert the one shared element exists (no per-page overlay markup).
    cy.get('#appAjaxOverlay', { timeout: 10000 }).should('exist')
  })
})

describe('Signup — validation & duplicate (server-side)', () => {
  // Seed the XSRF-TOKEN cookie so the commands.js request override can attach X-XSRF-TOKEN.
  beforeEach(() => {
    cy.visit('/registration.html', { failOnStatusCode: false })
  })

  it('rejects a missing organization name', () => {
    cy.request({
      method: 'POST',
      url: '/user/registration',
      form: true,
      failOnStatusCode: false,
      body: {
        firstName: 'No', lastName: 'Org',
        email: `noorg_${Date.now()}@example.com`,
        password: PW, matchingPassword: PW,
        // organizationName intentionally omitted -> UserDto @NotNull/@Size fails
      },
    }).then((res) => {
      expect(res.status).to.be.gte(400)
    })
  })

  it('rejects a duplicate email', () => {
    cy.request({
      method: 'POST',
      url: '/user/registration',
      form: true,
      failOnStatusCode: false,
      body: {
        firstName: 'Dup', lastName: 'User',
        organizationName: 'Dup Org',
        userType: 'BUSINESS', // valid so we reach the duplicate-email check (not a validation 400)
        email: 'demo.business@myplus.com', // already exists (seeded demo user)
        password: PW, matchingPassword: PW,
      },
    }).then((res) => {
      expect(res.status).to.be.gte(400)
    })
  })
})

describe('Signup — email verification gate', () => {
  // A freshly registered owner is disabled until they verify; logging in must NOT reach a dashboard.
  it('a freshly registered (unverified) user cannot log in', () => {
    const email = `unverified_${Date.now()}@example.com`
    cy.intercept('POST', '/user/registration').as('reg')
    cy.visit('/registration.html', { failOnStatusCode: false })
    fillForm({ org: 'Unverified Org', email })
    cy.get('button[type="submit"]').click({ force: true })
    // Confirm the account was actually created (else the login-block below would be a false positive).
    cy.wait('@reg').then(({ response }) => {
      expect(response.statusCode).to.eq(200)
      expect(response.body).to.have.property('status', 'success')
    })

    // Now attempt to log in with those credentials — must NOT reach a dashboard, and must show a
    // clear "verify your email" message (not the misleading "invalid credentials").
    cy.clearCookies()
    cy.visit('/login')
    cy.get('input[name="username"]').type(email)
    cy.get('input[name="password"]').type(PW)
    cy.get('#loginSubmit').click()
    cy.url().should('include', '/login?unverified')
    cy.get('.msg-bar.info').should('be.visible')
    cy.contains(/verify|confirmation link|trial account/i).should('be.visible')
  })
})

describe('Signup — redesigned split-panel UI (registration.html)', () => {
  beforeEach(() => {
    cy.clearCookies()
    cy.visit('/registration.html', { failOnStatusCode: false })
  })

  it('renders the shared login.css split-panel card and brand', () => {
    cy.get('.login-wrap').should('exist')
    cy.get('.login-left .brand-name').should('contain.text', 'MyPlus')
    cy.get('.login-card').should('be.visible')
    cy.get('.card-header h2').should('be.visible')
  })

  it('wires the branded submit button and "sign in" link to /login', () => {
    cy.get('button.btn-signin[type="submit"]').should('be.visible')
    cy.get('a.register-link').should('have.attr', 'href').and('match', /\/login$/)
  })

  it('lays out first/last name in the responsive grid', () => {
    cy.get('.field-grid input[name="firstName"]').should('exist')
    cy.get('.field-grid input[name="lastName"]').should('exist')
  })

  it('offers a required service/domain selector with the four domains', () => {
    cy.get('select[name="userType"]').should('exist').and('have.attr', 'required')
    cy.get('select[name="userType"] option').then(($opts) => {
      const values = [...$opts].map((o) => o.value)
      expect(values).to.include.members(['BUSINESS', 'EDUCATION', 'WELFARE', 'AGRICULTURE'])
    })
  })
})

describe('Signup — client-side validation (negative)', () => {
  beforeEach(() => {
    cy.clearCookies()
    cy.intercept('POST', '/user/registration').as('reg')
    cy.visit('/registration.html', { failOnStatusCode: false })
  })

  it('blocks submit and shows #globalError when passwords do not match', () => {
    cy.get('input[name="firstName"]').type('Mis')
    cy.get('input[name="lastName"]').type('Match')
    cy.get('input[name="organizationName"]').type('Mismatch Org')
    cy.get('select[name="userType"]').select('BUSINESS')
    cy.get('input[name="email"]').type(`mismatch_${Date.now()}@example.com`)
    cy.get('#password').type('Test@2025!')
    cy.get('#matchPassword').type('Different@2025!')
    cy.get('button[type="submit"]').click({ force: true })
    // register() returns early on mismatch: the error is shown and no POST is made.
    cy.get('#globalError').should('be.visible').and('not.have.text', '')
    cy.get('@reg.all').should('have.length', 0)
  })

  it('does not POST when required fields are empty (HTML5 required)', () => {
    cy.get('button[type="submit"]').click({ force: true })
    cy.get('input[name="firstName"]:invalid').should('exist')
    cy.get('@reg.all').should('have.length', 0)
  })

  it('does not POST when the email is malformed (HTML5 type=email)', () => {
    cy.get('input[name="firstName"]').type('Bad')
    cy.get('input[name="lastName"]').type('Email')
    cy.get('input[name="organizationName"]').type('Bad Email Org')
    cy.get('select[name="userType"]').select('BUSINESS')
    cy.get('input[name="email"]').type('not-an-email')
    cy.get('#password').type('Test@2025!')
    cy.get('#matchPassword').type('Test@2025!')
    cy.get('button[type="submit"]').click({ force: true })
    cy.get('input[name="email"]:invalid').should('exist')
    cy.get('@reg.all').should('have.length', 0)
  })

  it('requires choosing a service/domain before submit', () => {
    cy.get('input[name="firstName"]').type('NoDomain')
    cy.get('input[name="lastName"]').type('User')
    cy.get('input[name="organizationName"]').type('No Domain Org')
    cy.get('input[name="email"]').type(`nodomain_${Date.now()}@example.com`)
    cy.get('#password').type('Test@2025!')
    cy.get('#matchPassword').type('Test@2025!')
    // userType left unselected → HTML5 required blocks submit, no POST.
    cy.get('button[type="submit"]').click({ force: true })
    cy.get('select[name="userType"]:invalid').should('exist')
    cy.get('@reg.all').should('have.length', 0)
  })
})
