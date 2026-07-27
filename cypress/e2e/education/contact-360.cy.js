/**
 * Contact-360 in a NON-POS vertical (P4c). The same panel that business/POS got in P4b is now shared by
 * education, welfare and pharmacy — one component in /js/common/party-contact.js, one proxy (/partyRoles), one
 * owner/admin gate. This proves it on education: a student's role reaches the index, the dashboard actually
 * loads the shared component, and the panel renders the identity + an Education chip.
 *
 * Requires party + education + gateway + monolith up. Run headed.
 */
describe('Contact-360 — education (P4c)', () => {
  const parse = (b) => { try { return typeof b === 'string' ? JSON.parse(b) : b; } catch (e) { return {}; } }
  const rows = (b) => b.collection || b.data || []

  beforeEach(() => { cy.loginAsEducation() })   // demo.education carries SUPER_PRIVILEGE → allowed to view

  it('records a STUDENT role and shows it in the shared panel', () => {
    const stamp = Date.now()
    const name = 'C360Stu_' + stamp

    cy.request({
      method: 'POST', url: '/addStudent', form: true, failOnStatusCode: false,
      body: { name, enrollNo: `C3${stamp}`, mobile: '03' + String(stamp).slice(-9), email: `c3${stamp}@t.com`, status: 'ACTIVE' },
    }).then((r) => expect(JSON.stringify(r.body), 'student created').to.match(/SUCCESS/))

    cy.request('/getUserStudent').then((sr) => {
      const s = rows(sr.body).find((x) => x.name === name)
      expect(s, 'student row').to.exist
      expect(s.partyId, 'student bridged to a party').to.be.a('number')
      const pid = s.partyId

      // 1) The role reached party-service's index, through the same proxy the UI uses.
      cy.request(`/partyRoles?id=${pid}`).then((v) => {
        const d = parse(v.body)
        expect(d.party && d.party.id, 'identity in the view').to.eq(pid)
        const edu = (d.roles || []).find((x) => String(x.module).toLowerCase() === 'education' && x.role === 'STUDENT')
        expect(edu, 'education STUDENT role recorded').to.exist
        expect(edu.localId, 'points at the local student row').to.eq(s.id)
      })

      // 2) The education dashboard loads the SHARED component and is allowed to use it.
      cy.visit('/educationDashboard')
      cy.window().then((win) => {
        expect(win.canViewContact360, 'owner/admin gate passed on this dashboard').to.eq(true)
        expect(win.contact360Button, 'shared component loaded here').to.be.a('function')
        expect(win.contact360Button(pid), 'row action rendered when bridged').to.contain('openContact360')
        expect(win.contact360Button(null), 'no action for an unbridged row').to.eq('')
        win.openContact360(pid)
      })

      // 3) The panel renders the identity + the module chip.
      cy.get('.c360-card').should('be.visible').and('contain', name).and('contain', 'Education')
      cy.get('.c360-x').click()
      cy.get('.c360-backdrop').should('not.exist')
    })
  })
})
