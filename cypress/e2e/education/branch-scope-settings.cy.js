/**
 * Owner-configurable BRANCH scoping for staff and subjects.
 * Design: microservices/docs/slices/edu-branch-scope-settings.md
 *
 * A school group runs several campuses under one organization. Before this slice only guardians and
 * discounts could be narrowed to a campus — the staff list and the subject list were org-wide, so the
 * Karachi principal saw every Lahore teacher. Now the owner ticks a box per concern.
 *
 * The behaviours worth guarding are the ones that are easy to get subtly wrong:
 *   - OFF (the default) changes nothing;
 *   - owner/super and a caller with NO branch grants still see org-wide — otherwise the first admin to
 *     enable it meets an empty screen and assumes the data is gone;
 *   - a record attached to no class stays visible (design D4);
 *   - the PICKER is scoped too, or the dropdown becomes a way around the policy;
 *   - tenant isolation is untouched — that is not, and must never be, a setting.
 *
 * Uses the seeded multi-branch fixture (owner.education@ + teacher.a/b@), same as multi-branch.cy.js.
 *
 * Run headed:
 *   npx cypress open --e2e        (pick education/branch-scope-settings.cy.js)
 *   npx cypress run  --spec cypress/e2e/education/branch-scope-settings.cy.js
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const rows = (body) => {
  for (const key of ['collection', 'object', 'data']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

const form = (url, body) =>
  cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveConfig', form: true, body: { key, value }, failOnStatusCode: false })
    .then((r) => expect(JSON.stringify(r.body), `saveConfig ${key}=${value}`).to.match(/SUCCESS/))

describe('Education — owner-configurable branch scoping (staff & subjects)', () => {
  const F = {}

  before(() => {
    cy.loginAsEduOwner()

    // ── Two branches, reused by name across runs.
    cy.request('/getUserSchool').then((r) => {
      const existing = rows(r.body)
      const ensure = (branchName) => {
        const hit = existing.find((s) => s.branchName === branchName)
        if (hit) return cy.wrap(hit.id)
        return form('/addSchool', { name: branchName, branchName, status: 'Active' })
          .then(() => cy.request('/getUserSchool'))
          .then((rr) => cy.wrap(rows(rr.body).find((s) => s.branchName === branchName).id))
      }
      ensure('CY Branch 1').then((id) => { F.branch1 = id })
      ensure('CY Branch 2').then((id) => { F.branch2 = id })
    })

    // ── Grants: owner holds both branches; each teacher exactly one.
    cy.request('/team/users').then((r) => {
      const team = rows(r.body)
      const idOf = (email) => {
        const m = team.find((u) => u.email === email)
        expect(m, `seeded member ${email} missing — rebuild + restart auth-service`).to.exist
        return m.userId
      }
      const grant = (body) => cy.request({
        method: 'POST', url: '/assignStores', headers: { 'Content-Type': 'application/json' },
        body, failOnStatusCode: false,
      }).then((g) => expect(g.body && g.body.success, JSON.stringify(g.body)).to.eq(true))

      cy.then(() => grant({ storeIds: [F.branch1, F.branch2], roleAtLocation: 'OWNER' }))
      cy.then(() => grant({ userId: idOf('teacher.a@myplus.com'), storeIds: [F.branch1], roleAtLocation: 'USER' }))
      cy.then(() => grant({ userId: idOf('teacher.b@myplus.com'), storeIds: [F.branch2], roleAtLocation: 'USER' }))
    })

    // ── A class in each branch, plus staff and subjects attached to them.
    F.classA = `CY_CLASS_A_${uniq()}`
    F.classB = `CY_CLASS_B_${uniq()}`
    cy.then(() => form('/addGrade', { name: F.classA, schoolId: F.branch1, status: 'Active' }))
    cy.then(() => form('/addGrade', { name: F.classB, schoolId: F.branch2, status: 'Active' }))

    cy.then(() => cy.request('/getUserGrade')).then((r) => {
      const all = rows(r.body)
      F.classAId = all.find((g) => g.name === F.classA).id
      F.classBId = all.find((g) => g.name === F.classB).id
    })

    F.staffA = `CY_STAFF_A_${uniq()}`
    F.staffB = `CY_STAFF_B_${uniq()}`
    F.staffNone = `CY_STAFF_NONE_${uniq()}`
    F.subjA = `CY_SUBJ_A_${uniq()}`
    F.subjB = `CY_SUBJ_B_${uniq()}`

    cy.then(() => form('/addStaff', { name: F.staffA, designation: 'Teacher', status: 'Active', gradeIds: F.classAId }))
    cy.then(() => form('/addStaff', { name: F.staffB, designation: 'Teacher', status: 'Active', gradeIds: F.classBId }))
    // Deliberately assigned to NO class — design D4.
    cy.then(() => form('/addStaff', { name: F.staffNone, designation: 'Admin', status: 'Active' }))

    cy.then(() => form('/addSubject', { name: F.subjA, code: `SA${uniq()}`, status: 'Active', gradeId: F.classAId }))
    cy.then(() => form('/addSubject', { name: F.subjB, code: `SB${uniq()}`, status: 'Active', gradeId: F.classBId }))
  })

  // Leave the org as we found it, or later specs inherit a narrowed view.
  after(() => {
    cy.loginAsEduOwner()
    setConfig('edu.staff.branchScoped', 'false')
    setConfig('edu.subject.branchScoped', 'false')
  })

  const staffNames = () => cy.request('/getUserStaff').then((r) => rows(r.body).map((s) => s.name))
  const subjectNames = () => cy.request('/getUserSubject').then((r) => rows(r.body).map((s) => s.name))

  // ── The settings are offered at all ────────────────────────────────────────

  it('the owner is offered both toggles under "Branch policy"', () => {
    cy.loginAsEduOwner()
    cy.request('/getConfig').then((r) => {
      const keys = rows(r.body).map((s) => s.key)
      expect(keys, 'staff toggle is on the Configuration screen').to.include('edu.staff.branchScoped')
      expect(keys, 'subject toggle is on the Configuration screen').to.include('edu.subject.branchScoped')

      const staffEntry = rows(r.body).find((s) => s.key === 'edu.staff.branchScoped')
      expect(staffEntry.group, 'grouped with the other branch policies').to.eq('Branch policy')
      expect(String(staffEntry.value), 'default OFF — an existing group must not change behaviour').to.eq('false')
    })
  })

  // ── Staff ──────────────────────────────────────────────────────────────────

  describe('Staff', () => {

    it('OFF (default): a branch teacher still sees every campus', () => {
      cy.loginAsEduOwner()
      setConfig('edu.staff.branchScoped', 'false')

      cy.loginAsTeacherA()
      staffNames().then((names) => {
        expect(names, 'the default must not change what anyone sees').to.include.members([F.staffA, F.staffB])
      })
    })

    it('ON: a Branch 1 teacher sees Branch 1 staff, not Branch 2', () => {
      cy.loginAsEduOwner()
      setConfig('edu.staff.branchScoped', 'true')

      cy.loginAsTeacherA()
      staffNames().then((names) => {
        expect(names, 'own branch').to.include(F.staffA)
        expect(names, "another campus's teacher is hidden").to.not.include(F.staffB)
      })
    })

    it('ON: staff assigned to no class stay visible (design D4)', () => {
      cy.loginAsEduOwner()
      setConfig('edu.staff.branchScoped', 'true')

      cy.loginAsTeacherA()
      staffNames().then((names) => {
        expect(names, 'a record with no derivable branch must not vanish when the toggle flips')
          .to.include(F.staffNone)
      })
    })

    it('ON: the owner still sees every campus', () => {
      cy.loginAsEduOwner()
      setConfig('edu.staff.branchScoped', 'true')

      staffNames().then((names) => {
        expect(names, 'the owner runs the group — the policy is for branch staff')
          .to.include.members([F.staffA, F.staffB])
      })
    })

    it('ON: the picker is scoped too, not just the table', () => {
      cy.loginAsEduOwner()
      setConfig('edu.staff.branchScoped', 'true')

      cy.loginAsTeacherA()
      cy.request('/getUserStaffs').then((r) => {
        const html = String(r.body)
        expect(html).to.contain(F.staffA)
        expect(html, 'a dropdown offering what the list hides is a way around the policy')
          .to.not.contain(F.staffB)
      })
    })
  })

  // ── Subjects ───────────────────────────────────────────────────────────────

  describe('Subjects', () => {

    it('OFF (default): the whole curriculum is visible', () => {
      cy.loginAsEduOwner()
      setConfig('edu.subject.branchScoped', 'false')

      cy.loginAsTeacherA()
      subjectNames().then((names) => {
        expect(names).to.include.members([F.subjA, F.subjB])
      })
    })

    it('ON: a Branch 1 teacher sees Branch 1 subjects only', () => {
      cy.loginAsEduOwner()
      setConfig('edu.subject.branchScoped', 'true')

      cy.loginAsTeacherA()
      subjectNames().then((names) => {
        expect(names).to.include(F.subjA)
        expect(names).to.not.include(F.subjB)
      })
    })

    it('ON: the subject picker is scoped too', () => {
      cy.loginAsEduOwner()
      setConfig('edu.subject.branchScoped', 'true')

      cy.loginAsTeacherA()
      cy.request('/getUserSubjects').then((r) => {
        const html = String(r.body)
        expect(html).to.contain(F.subjA)
        expect(html).to.not.contain(F.subjB)
      })
    })
  })

  // ── The boundary that is NOT configurable ──────────────────────────────────

  it('tenant isolation is unaffected by any branch setting', () => {
    // A different tenant entirely. No combination of branch toggles may reveal this org's staff to them —
    // branch policy narrows visibility WITHIN an org; it can never widen it beyond one.
    cy.loginAsEduOwner()
    setConfig('edu.staff.branchScoped', 'false')   // most permissive branch setting

    cy.loginAsEducation()   // demo.education@ — a different seeded org
    staffNames().then((names) => {
      expect(names, "another tenant's staff must never be visible, whatever the branch policy says")
        .to.not.include.members([F.staffA, F.staffB, F.staffNone])
    })
  })
})
