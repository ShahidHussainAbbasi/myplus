/**
 * Owner Configuration — the generic per-tenant settings store (pilot).
 *
 * Proves the pattern end to end: the catalog is served, an owner toggles a policy, and BEHAVIOUR changes for
 * that org only. The pilot registers two branch-visibility policies (guardian, discount), both default OFF
 * (org-wide). Here we drive edu.guardian.branchScoped: default off => a branch-2 teacher sees a branch-1
 * guardian; turn it on => they don't; restore off.
 *
 * Reuses the multi-branch fixture accounts (owner.education + teacher.a/b, seeded dev-only).
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const rows = (body) => {
  for (const key of ['collection', 'object', 'data']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

describe('Owner Configuration: per-tenant settings store', () => {
  const GKEY = 'edu.guardian.branchScoped'

  const setConfig = (key, value) =>
    cy.request({ method: 'POST', url: '/saveConfig', form: true, body: { key, value }, failOnStatusCode: false })
      .then((r) => expect(JSON.stringify(r.body), `saveConfig ${key}=${value}`).to.match(/SUCCESS/))

  it('serves the settings catalog with effective values', () => {
    cy.loginAsEduOwner()
    cy.request('/getConfig').then((r) => {
      const items = rows(r.body)
      expect(items.length, 'catalog has entries').to.be.greaterThan(0)
      const g = items.find((i) => i.key === GKEY)
      expect(g, 'guardian branch policy is registered').to.exist
      expect(g.type).to.eq('BOOL')
      expect(String(g.value), 'defaults OFF').to.eq('false')
    })
  })

  it('rejects an unknown setting key', () => {
    cy.loginAsEduOwner()
    cy.request({ method: 'POST', url: '/saveConfig', form: true,
      body: { key: 'edu.not.a.real.key', value: 'true' }, failOnStatusCode: false })
      .then((r) => expect(JSON.stringify(r.body), 'unknown key refused').to.not.match(/SUCCESS/))
  })

  it('toggling guardian branch scoping changes what a teacher sees', () => {
    // Fixture: a branch-1 guardian referenced by a branch-1 student.
    const gname = `CY_G_${uniq()}`
    cy.loginAsEduOwner()
    cy.request('/getUserSchool').then((r) => {
      const b1 = rows(r.body).find((s) => s.branchName === 'CY Branch 1')
      expect(b1, 'branch 1 exists (run multi-branch.cy.js fixture first)').to.exist

      cy.request({ method: 'POST', url: '/addGuardian', form: true,
        body: { name: gname, cnic: `C${uniq()}`, status: 'ACTIVE' }, failOnStatusCode: false })
        .then((gr) => expect(JSON.stringify(gr.body)).to.match(/SUCCESS/))

      cy.request('/getUserGuardian').then((gg) => {
        const g = rows(gg.body).find((x) => x.name === gname)
        expect(g, 'guardian created').to.exist
        // A branch-1 student that references this guardian.
        cy.request({ method: 'POST', url: '/addStudent', form: true,
          body: { name: `CY_GS_${uniq()}`, enrollNo: `EN${uniq()}`, status: 'ACTIVE',
                  schoolId: b1.id, guardianId: g.id }, failOnStatusCode: false })
          .then((sr) => expect(JSON.stringify(sr.body)).to.match(/SUCCESS/))

        const teacherBSees = (shouldSee) =>
          cy.request('/getUserGuardian').then((r2) => {
            const seen = rows(r2.body).map((x) => x.name).includes(gname)
            expect(seen, `branch-2 teacher ${shouldSee ? 'sees' : 'does NOT see'} the branch-1 guardian`).to.eq(shouldSee)
          })

        // Default OFF (org-wide) -> teacher B sees it.
        cy.loginAsTeacherB()
        teacherBSees(true)

        // Owner turns the policy ON -> teacher B no longer sees it.
        cy.loginAsEduOwner()
        cy.then(() => setConfig(GKEY, 'true'))
        cy.loginAsTeacherB()
        teacherBSees(false)

        // Restore OFF so the org is clean for reruns / other specs.
        cy.loginAsEduOwner()
        cy.then(() => setConfig(GKEY, 'false'))
      })
    })
  })
})
