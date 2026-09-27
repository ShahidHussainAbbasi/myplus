/**
 * Cross-tenant delete IDOR — the regression gate for the security fix.
 *
 * Every education deleteX endpoint used to run `repo.deleteById(rawIdFromRequest)` with NO ownership check,
 * so any authenticated education user could delete ANOTHER ORGANIZATION's rows by guessing ids. This spec
 * drives exactly that attack across two real tenants and asserts the victim's data survives.
 *
 * The tenants: owner.education@ (its own seeded org) and demo.education@ (a different seeded org). They are
 * both legitimate education logins, which is the whole point — this was never about privilege level, it was
 * about a missing tenant check.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const rows = (body) => {
  for (const key of ['collection', 'object', 'data']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

// cy.wrap(... || null), never a bare `find`: a .then() that returns undefined makes Cypress yield the PREVIOUS
// subject (the request's Response), so "student is gone" would silently assert against a Response object.
const findStudent = (name) =>
  cy.request('/getUserStudent').then((r) => cy.wrap(rows(r.body).find((s) => s.name === name) || null))

describe('Security: a tenant cannot delete another tenant\'s rows', () => {
  const victim = `CY_VICTIM_${uniq()}`
  let victimId

  before(() => {
    // Tenant A (owner.education) owns a student.
    cy.loginAsEduOwner()
    cy.request({
      method: 'POST', url: '/addStudent', form: true,
      body: { name: victim, enrollNo: `EN${uniq()}`, status: 'ACTIVE' },
      failOnStatusCode: false,
    }).then((r) => expect(JSON.stringify(r.body), 'victim student created').to.match(/SUCCESS/))

    findStudent(victim).then((s) => {
      expect(s, 'victim student is readable by its owner').to.exist
      victimId = s.id
    })
  })

  it('another org cannot delete a student it does not own', () => {
    // Tenant B attacks with the id it should never be able to touch.
    cy.loginAsEducation()   // demo.education@ — a different organization
    cy.then(() => {
      cy.request({
        method: 'POST', url: '/deleteStudent', form: true,
        body: { checked: String(victimId) }, failOnStatusCode: false,
      })
      // The endpoint may well answer "true" (the request was well-formed); what matters is that nothing died.
      // It also must not have deleted it silently — so verify from the OWNER's side, which is the real proof.
      cy.loginAsEduOwner()
      findStudent(victim).then((still) => {
        expect(still, `another org deleted our student (id ${victimId}) — the IDOR is back`).to.exist
      })
    })
  })

  /*
   * ⭐⭐ THE SAME DEFECT, ON THE REST-STYLE ALERT CONTROLLERS — proven live 2026-09-26.
   *
   * AlertsController and AlertChannelController were added later than the rest of the module and in a
   * different style, and they skipped the tenant check the other twenty controllers apply. Their LIST
   * endpoint scopes by user, but get/update/delete resolved a client-supplied id through a bare
   * findById -- so any education login could read, rewrite or delete another school's row.
   *
   * ⚠ Reachable through a DOUBLED prefix. The gateway strips two segments (StripPrefix=2) and these two
   * controllers are mapped at "/api/education/alerts" rather than the bare "/getUserAlerts" style the
   * module uses -- so the routable URL is /api/education/api/education/alerts/{id}. It looks like a typo
   * and is a real, working route; the odd shape is exactly why this went unnoticed.
   *
   * Demonstrated before the fix: as owner.education@ (org 13), GET on alert 11 (org 24) returned the whole
   * row -- "Holiday Notice / School closed Friday."
   *
   * The repository already had findByIdScoped, with a docstring saying it guards these very paths.
   */
  it('⭐⭐ another org cannot read an alert it does not own (doubled-prefix REST route)', () => {
    const GW = 'http://localhost:8765'
    cy.request({
      method: 'POST', url: `${GW}/api/auth/login`,
      headers: { 'Content-Type': 'application/json' },
      body: { email: 'owner.education@myplus.com', password: 'Demo@2025!' },
    }).then((login) => {
      const token = login.body.data.accessToken
      // Find an alert belonging to somebody else by walking ids: this tenant's own list is the control.
      cy.request({
        url: `${GW}/api/education/api/education/alerts?page=0&size=50`,
        headers: { Authorization: `Bearer ${token}` }, failOnStatusCode: false,
      }).then((mine) => {
        const own = new Set(((mine.body && mine.body.data && mine.body.data.content) || [])
          .map((a) => a.id))
        // ids 1..40 covers the seeded range; any hit NOT in `own` is another tenant's row.
        const probes = Array.from({ length: 40 }, (_, i) => i + 1).filter((id) => !own.has(id))
        cy.wrap(probes).each((id) => {
          cy.request({
            url: `${GW}/api/education/api/education/alerts/${id}`,
            headers: { Authorization: `Bearer ${token}` }, failOnStatusCode: false,
          }).then((r) => {
            const leaked = r.status === 200 && r.body && r.body.success === true && r.body.data
            expect(leaked, `alert ${id} belongs to another tenant and was returned in full: `
              + JSON.stringify(r.body && r.body.data).slice(0, 140)).to.not.be.ok
          })
        })
      })
    })
  })

  it('the owner can still delete their own student', () => {
    // The guard must not have broken the legitimate path — a deny-everything fix would also pass the test above.
    cy.loginAsEduOwner()
    cy.then(() => {
      cy.request({
        method: 'POST', url: '/deleteStudent', form: true,
        body: { checked: String(victimId) }, failOnStatusCode: false,
      })
      findStudent(victim).then((gone) => {
        expect(gone, 'the owner\'s own delete still works').to.be.null
      })
    })
  })
})
