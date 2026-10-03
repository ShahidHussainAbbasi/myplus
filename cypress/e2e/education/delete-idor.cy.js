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
   * Demonstrated before the fix: as owner.education@ (org 14), GET on alert 11 (org 24) returned the whole
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

/*
 * ⭐⭐ EDU-IDOR-2 — an EDIT, or a LINK, reaching into another tenant (2026-10-03).
 *
 * addStudent / addGrade / addVehicle loaded the edited row with a bare findById(dto.id) and then stamped the
 * CALLER's organizationId onto it. The only check in between was canAccessSchool, which looks at the BRANCH,
 * never the org, and is true for an owner. So an edit naming another school's row MOVED it into the caller's
 * school. addStudent also stored gradeId/guardianId/discountId/vehicleId straight from the form, and the fee is
 * computed from the linked class -- a foreign class billed another school's fee.
 *
 * Victim = demo.education@ (its own org). Attacker = owner.education@ (a different org, uncapped, ROLE_OWNER --
 * the caller canAccessSchool waves through). Only rows THIS block creates are attacked, and after() removes them
 * from BOTH tenants -- on a vulnerable build they will have moved.
 */
describe('Security EDU-IDOR-2: a tenant cannot edit, re-file or link another tenant\'s rows', () => {
  const tag = `CY_IDOR2_${uniq()}`
  const victim = { student: `${tag}_pupil`, grade: `${tag}_class`, vehicle: `${tag}_bus` }
  const ids = {}

  const post = (url, body) =>
    cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })
  const status = (r) => (r.body && r.body.status) || `HTTP ${r.status}`
  // A list helper must FAIL LOUDLY on a non-list -- an empty array from a 403 would make "not there" pass.
  const list = (url) => cy.request(url).then((r) => {
    expect(r.status, `${url} answered`).to.eq(200)
    return cy.wrap(rows(r.body))
  })
  const byName = (url, name, field = 'name') =>
    list(url).then((all) => cy.wrap(all.find((x) => x[field] === name) || null))

  before(() => {
    cy.loginAsEducation()   // victim tenant
    list('/getUserSchool').then((schools) => {
      expect(schools.length, 'the victim tenant has a school to aim at').to.be.greaterThan(0)
      ids.victimSchool = schools[0].id
    })
    post('/addGrade', { name: victim.grade, status: 'ACTIVE' })
      .then((r) => expect(status(r), 'victim class created').to.eq('SUCCESS'))
    post('/addVehicle', { name: victim.vehicle, number: victim.vehicle, status: 'ACTIVE' })
      .then((r) => expect(status(r), 'victim vehicle created').to.eq('SUCCESS'))
    post('/addStudent', { name: victim.student, enrollNo: `EN${uniq()}`, status: 'ACTIVE' })
      .then((r) => expect(status(r), 'victim student created').to.eq('SUCCESS'))
    byName('/getUserGrade', victim.grade).then((g) => { expect(g, 'victim class listed').to.exist; ids.grade = g.id })
    byName('/getUserVehicle', victim.vehicle, 'number').then((v) => { expect(v, 'victim vehicle listed').to.exist; ids.vehicle = v.id })
    byName('/getUserStudent', victim.student).then((s) => { expect(s, 'victim student listed').to.exist; ids.student = s.id })
  })

  after(() => {
    // Delete by name from BOTH tenants: a red run leaves the hijacked rows in the ATTACKER's org.
    const sweep = () => {
      list('/getUserStudent').then((all) => {
        const mine = all.filter((x) => String(x.name || '').startsWith(tag)).map((x) => x.id)
        if (mine.length) post('/deleteStudent', { checked: mine.join(',') })
      })
      list('/getUserGrade').then((all) => {
        const mine = all.filter((x) => String(x.name || '').startsWith(tag)).map((x) => x.id)
        if (mine.length) post('/deleteGrade', { checked: mine.join(',') })
      })
      list('/getUserVehicle').then((all) => {
        const mine = all.filter((x) => String(x.number || x.name || '').startsWith(tag)).map((x) => x.id)
        if (mine.length) post('/deleteVehicle', { checked: mine.join(',') })
      })
    }
    cy.loginAsEduOwner(); sweep()
    cy.loginAsEducation(); sweep()
  })

  const attack = (url, body, expected) => {
    cy.loginAsEduOwner()
    cy.then(() => post(url, body(ids)).then((r) => {
      expect(status(r), `${url} ${JSON.stringify(body(ids))} must be refused`).to.eq(expected)
    }))
  }

  it('editing another tenant\'s STUDENT is NOT_FOUND, and the victim still has it', () => {
    attack('/addStudent', (i) => ({ id: i.student, name: `${tag}_HIJACKED`, status: 'ACTIVE' }), 'NOT_FOUND')
    cy.loginAsEducation()
    byName('/getUserStudent', victim.student).then((s) => {
      expect(s, 'the pupil was taken from its school -- the edit IDOR is back').to.exist
    })
  })

  it('editing another tenant\'s CLASS is NOT_FOUND, and the victim still has it', () => {
    attack('/addGrade', (i) => ({ id: i.grade, name: `${tag}_HIJACKED`, status: 'ACTIVE' }), 'NOT_FOUND')
    cy.loginAsEducation()
    byName('/getUserGrade', victim.grade).then((g) => expect(g, 'the class was taken').to.exist)
  })

  it('editing another tenant\'s VEHICLE is NOT_FOUND, and the victim still has it', () => {
    attack('/addVehicle', (i) => ({ id: i.vehicle, name: victim.vehicle, number: `${tag}_HIJACKED`, status: 'ACTIVE' }), 'NOT_FOUND')
    cy.loginAsEducation()
    byName('/getUserVehicle', victim.vehicle, 'number').then((v) => expect(v, 'the vehicle was taken').to.exist)
  })

  it('a student cannot be linked to another tenant\'s CLASS (the fee would be billed from it)', () => {
    attack('/addStudent', (i) => ({ name: `${tag}_linked`, gradeId: i.grade, status: 'ACTIVE' }), 'FAILED')
    cy.loginAsEduOwner()
    byName('/getUserStudent', `${tag}_linked`).then((s) => expect(s, 'a student was saved on a foreign class').to.be.null)
  })

  it('a student cannot be linked to another tenant\'s VEHICLE', () => {
    attack('/addStudent', (i) => ({ name: `${tag}_bus_link`, vehicleId: i.vehicle, status: 'ACTIVE' }), 'FAILED')
  })

  it('a class cannot be filed under another tenant\'s SCHOOL', () => {
    attack('/addGrade', (i) => ({ name: `${tag}_refiled`, schoolId: i.victimSchool, status: 'ACTIVE' }), 'FAILED')
  })

  it('CONTROL: the attacker\'s OWN student and class still save and link (a deny-all guard fails here)', () => {
    cy.loginAsEduOwner()
    post('/addGrade', { name: `${tag}_own_class`, status: 'ACTIVE' })
      .then((r) => expect(status(r), 'own class created').to.eq('SUCCESS'))
    byName('/getUserGrade', `${tag}_own_class`).then((g) => {
      expect(g, 'own class listed').to.exist
      post('/addStudent', { name: `${tag}_own_pupil`, gradeId: g.id, status: 'ACTIVE' })
        .then((r) => expect(status(r), 'own pupil on own class').to.eq('SUCCESS'))
    })
    byName('/getUserStudent', `${tag}_own_pupil`).then((s) => {
      expect(s, 'own pupil listed').to.exist
      post('/addStudent', { id: s.id, name: `${tag}_own_pupil_renamed`, status: 'ACTIVE' })
        .then((r) => expect(status(r), 'own pupil edited').to.eq('SUCCESS'))
    })
    byName('/getUserStudent', `${tag}_own_pupil_renamed`).then((s) => expect(s, 'the edit landed').to.exist)
  })
})
