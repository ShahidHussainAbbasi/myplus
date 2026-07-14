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
