/**
 * Multi-branch (Schools) — role×branch visibility. The P4 gate for
 * microservices/docs/multi-location-stores-branches-design.md (§5, T9 + the branch switcher).
 *
 * The education twin of business/multi-location.cy.js: School IS the branch (it already existed), and the
 * grants that scope it live in the same central table as store grants — only the module differs (EDUCATION vs
 * BUSINESS), which auth derives from the user's own vertical.
 *
 * One deliberate difference from POS, asserted below: a teacher sees their BRANCH's whole roster, not merely
 * the students they personally entered. A cashier's till is private; a school's roster is shared by its staff.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

// GenericResponse puts a list in `collection` (and serialises `object` as null) — take the first key that
// actually holds an array. ApiResponse (auth) uses `data`.
const rows = (body) => {
  for (const key of ['collection', 'object', 'data']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

// Education writes are form-encoded (the monolith relays request params straight through).
const addStudent = (name, schoolId) =>
  cy.request({
    method: 'POST', url: '/addStudent', form: true,
    body: { name, enrollNo: `EN${uniq()}`, status: 'ACTIVE', schoolId },
    failOnStatusCode: false,
  }).then((r) => {
    expect(JSON.stringify(r.body), `addStudent ${name}`).to.match(/SUCCESS/)
  })

describe('Multi-branch: schools, grants and role×branch visibility', () => {
  const F = {}   // branch1, branch2, teacher ids, student names

  before(() => {
    cy.loginAsEduOwner()

    // Branches are created once and reused by name across runs.
    cy.request('/getUserSchool').then((r) => {
      const existing = rows(r.body)
      const ensure = (branchName) => {
        const hit = existing.find((s) => s.branchName === branchName)
        if (hit) return cy.wrap(hit.id)
        return cy.request({
          method: 'POST', url: '/addSchool', form: true,
          body: { name: branchName, branchName, status: 'ACTIVE' },
        }).then(() => cy.request('/getUserSchool').then((again) => {
          const made = rows(again.body).find((s) => s.branchName === branchName)
          expect(made, `branch ${branchName} created`).to.exist
          return made.id
        }))
      }
      ensure('CY Branch 1').then((id) => { F.branch1 = id })
      ensure('CY Branch 2').then((id) => { F.branch2 = id })
    })

    // Grants. The owner self-grants BOTH branches (userId omitted = self) so they can switch between them;
    // each teacher gets exactly one. Idempotent, so re-runs are safe.
    cy.request('/team/users').then((r) => {
      const team = rows(r.body)
      const idOf = (email) => {
        const m = team.find((u) => u.email === email)
        expect(m, `seeded member ${email} missing — rebuild + restart auth-service (SetupDataLoader)`).to.exist
        return m.userId
      }
      F.teacherAId = idOf('teacher.a@myplus.com')
      F.teacherBId = idOf('teacher.b@myplus.com')

      const grant = (body) => cy.request({
        method: 'POST', url: '/assignStores', headers: { 'Content-Type': 'application/json' },
        body, failOnStatusCode: false,
      }).then((g) => {
        expect(g.body && g.body.success, `grant ${JSON.stringify(body)}: ${JSON.stringify(g.body)}`).to.eq(true)
      })

      cy.then(() => grant({ storeIds: [F.branch1, F.branch2], roleAtLocation: 'OWNER' }))          // self
      cy.then(() => grant({ userId: F.teacherAId, storeIds: [F.branch1], roleAtLocation: 'USER' }))
      cy.then(() => grant({ userId: F.teacherBId, storeIds: [F.branch2], roleAtLocation: 'USER' }))
    })

    // A student in each branch, entered by the OWNER — so the teacher-sees-the-roster rule is tested with
    // students the teacher did not create (own-only scoping would wrongly hide these).
    F.student1 = `CY_S1_${uniq()}`
    F.student2 = `CY_S2_${uniq()}`
    cy.then(() => addStudent(F.student1, F.branch1))
    cy.then(() => addStudent(F.student2, F.branch2))
  })

  // T9 — the headline: a teacher at Branch 1 sees Branch 1's students, and not Branch 2's.
  it('T9: teacher at Branch 1 sees Branch 1 students only', () => {
    cy.loginAsTeacherA()
    cy.request('/getUserStudent').then((r) => {
      const names = rows(r.body).map((s) => s.name)
      expect(names, 'sees their own branch').to.include(F.student1)
      expect(names, 'does NOT see the other branch').to.not.include(F.student2)
    })
  })

  // The mirror image, so a pass cannot be an artefact of one teacher simply seeing everything.
  it('T9b: teacher at Branch 2 sees Branch 2 students only', () => {
    cy.loginAsTeacherB()
    cy.request('/getUserStudent').then((r) => {
      const names = rows(r.body).map((s) => s.name)
      expect(names, 'sees their own branch').to.include(F.student2)
      expect(names, 'does NOT see the other branch').to.not.include(F.student1)
    })
  })

  // The deliberate deviation from POS: the roster is shared, so a teacher sees students they did not enter.
  it('T9c: a teacher sees their branch\'s roster, not just students they created', () => {
    cy.loginAsTeacherA()
    cy.request('/getUserStudent').then((r) => {
      const mine = rows(r.body).find((s) => s.name === F.student1)
      expect(mine, 'the owner-entered student is visible to the branch teacher').to.exist
      expect(Number(mine.userId), 'and it really was entered by someone else').to.not.eq(Number(F.teacherAId))
    })
  })

  // The owner is never narrowed by grants — both branches, always.
  it('T9d: owner sees every branch', () => {
    cy.loginAsEduOwner()
    cy.request('/getUserStudent').then((r) => {
      const names = rows(r.body).map((s) => s.name)
      expect(names).to.include(F.student1)
      expect(names).to.include(F.student2)
    })
  })

  // Anti-IDOR: a teacher cannot file a student into a branch they do not hold.
  it('T9e: teacher cannot create a student in another branch', () => {
    cy.loginAsTeacherA()
    cy.request({
      method: 'POST', url: '/addStudent', form: true,
      body: { name: `CY_LEAK_${uniq()}`, enrollNo: `EN${uniq()}`, status: 'ACTIVE', schoolId: F.branch2 },
      failOnStatusCode: false,
    }).then((r) => {
      expect(JSON.stringify(r.body), 'writing into another branch must be refused').to.not.match(/SUCCESS/)
    })
  })

  // The Manage Users screen education never had. It drives the same vertical-agnostic endpoints as the POS
  // one (/team/users + the storeIds grant list), and auth resolves those ids as SCHOOLS from the caller's
  // vertical — so a teacher created here is scoped to the branch the owner picked.
  it('T9g: owner can add a teacher and assign them a branch', () => {
    cy.loginAsEduOwner()
    const email = `cy.teacher.${uniq()}@myplus.com`
    cy.request({
      method: 'POST', url: '/team/users', headers: { 'Content-Type': 'application/json' },
      body: { firstName: 'CY', lastName: 'Teacher', email, role: 'USER', storeIds: [F.branch1] },
      failOnStatusCode: false,
    }).then((r) => {
      const created = (r.body && (r.body.data || r.body)) || {}
      expect(created.email, `teacher creation failed: ${JSON.stringify(r.body)}`).to.eq(email)
      expect(String(created.role)).to.eq('USER')
    })
    // ...and they show up on the team the screen lists.
    cy.request('/team/users').then((r) => {
      expect(rows(r.body).map((u) => u.email), 'new teacher appears in the team list').to.include(email)
    })
  })

  // The branch switcher (the education twin of T10): switching re-issues the JWT, and the next student
  // created without an explicit branch is filed under the switched-to one.
  it('T9f: switching the active branch files the next student under it', () => {
    cy.loginAsEduOwner()
    cy.request('/getMySchools').then((r) => {
      expect(rows(r.body).length, 'owner can work at both branches').to.be.greaterThan(1)

      cy.request({
        method: 'POST', url: '/switchStore', headers: { 'Content-Type': 'application/json' },
        body: { storeId: F.branch2 }, failOnStatusCode: false,
      }).then((s) => expect(s.body.status, `switch branch: ${JSON.stringify(s.body)}`).to.eq('SUCCESS'))

      cy.request('/getMySchools').then((after) => {
        const active = rows(after.body).find((b) => b.active)
        expect(active && Number(active.id), 'Branch 2 is now active').to.eq(Number(F.branch2))
      })

      // No schoolId in the payload — it must inherit the active branch.
      const stamped = `CY_STAMP_${uniq()}`
      cy.request({
        method: 'POST', url: '/addStudent', form: true,
        body: { name: stamped, enrollNo: `EN${uniq()}`, status: 'ACTIVE' },
        failOnStatusCode: false,
      }).then((r) => expect(JSON.stringify(r.body)).to.match(/SUCCESS/))

      cy.request('/getUserStudent').then((sr) => {
        const made = rows(sr.body).find((s) => s.name === stamped)
        expect(made, 'the new student exists').to.exist
        expect(Number(made.schoolId), 'filed under the switched-to branch').to.eq(Number(F.branch2))
      })
    })
  })
})
