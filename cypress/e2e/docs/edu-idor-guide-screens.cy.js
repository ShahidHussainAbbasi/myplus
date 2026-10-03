/**
 * EDU-IDOR-2 — "one school cannot touch another school's records", as REAL manual test cases for the Test Book.
 *
 * Each `it` is one complete manual case — prerequisites, test data, numbered actions, the expected result of
 * each, and the cleanup — and every action is PERFORMED, ASSERTED and PHOTOGRAPHED in that order. The Test Book
 * section is built from the JSON this writes, and shows a case as passed only if the whole case passed.
 *
 * WHY MOST ATTACK STEPS ARE CONSOLE COMMANDS. The attack is precisely the thing no screen offers: a school's
 * pickers only list its OWN classes, vehicles and schools, and its edit form only opens its OWN rows. A real
 * attacker sends the request by hand. So those steps give the tester the exact browser-console command, and
 * this spec RUNS THAT SAME COMMAND in the page (`win.eval`) — the command on the page is tested too.
 *
 * TENANTS. The victim is demo.education@ and the attacker is owner.education@ (a different organisation, and an
 * OWNER — the caller the old branch check waved through). Only rows a case creates itself are attacked. Every
 * case registers an undo in SAFETY, run in after(), so a case that fails half-way still leaves nothing behind.
 *
 * Run headed:
 *   npx cypress run --headed --browser electron --spec cypress/e2e/docs/edu-idor-guide-screens.cy.js
 * One case:  --env guideOnly=E2
 */
const OUT_DIR = 'cypress/guide-out/edu-idor'
const ONLY = String(Cypress.env('guideOnly') || '').split(',').map((x) => x.trim()).filter(Boolean)
const caseIt = (id, title, fn) => ((ONLY.length && !ONLY.includes(id)) ? it.skip : it)(`${id} — ${title}`, fn)
const VICTIM = 'demo.education@myplus.com'
const ATTACKER = 'owner.education@myplus.com'
const run = String(Date.now()).slice(-5)
const TAG = `IDOR${run}`

const SAFETY = []
let cur = null

const testCase = (id, title, meta) => {
  cur = { id, section: 'One school cannot touch another school\'s records', title, shots: [], actions: [], cleanup: [], ...meta }
}
const act = (text, expect, opts = {}) => {
  const a = { do: text, expect: [].concat(expect || []), shots: [], via: opts.via || 'screen', code: opts.code || null, as: opts.as || null }
  ;(opts.cleanup ? cur.cleanup : cur.actions).push(a)
  return a
}
const snap = (a, name, subject) => {
  const pos = cur.actions.includes(a) ? `a${cur.actions.indexOf(a) + 1}` : `c${cur.cleanup.indexOf(a) + 1}`
  const file = `${cur.id}-${pos}-${name}`
  a.shots.push(file)
  cur.shots.push(file)
  return subject ? cy.get(subject).screenshot(file, { overwrite: true })
                 : cy.screenshot(file, { capture: 'viewport', overwrite: true })
}

const list = (b) => { for (const k of ['collection', 'data', 'object']) if (Array.isArray(b && b[k])) return b[k]; return Array.isArray(b) ? b : [] }
const asVictim = () => cy.loginAsEducation()
const asAttacker = () => cy.loginAsEduOwner()

// ── screens ────────────────────────────────────────────────────────────────────────────────────────────────
const SECTION = { Grade: 'GradeDiv', Vehicle: 'VehicleDiv', Student: 'StudentDiv' }
const MENU = { Grade: 'Academics → Classes', Vehicle: 'School → Vehicles', Student: 'Students → Manage Students' }
const openList = (entity) => cy.openSection(SECTION[entity], '/educationDashboard')
/** Type into the grid's own search box; the grid then shows only matching rows. */
const search = (entity, text) => {
  cy.get(`#table${entity}_wrapper input[type="search"]`).first().clear().type(text)
}
const gridHas = (entity, text) => cy.get(`#table${entity} tbody`).should('contain', text)
const gridLacks = (entity, text) => cy.get(`#table${entity} tbody`).should('not.contain', text)
/** Choose the first real option of a (bootstrap-select backed) <select>. */
const pickFirst = (sel) =>
  cy.get(`${sel} option`, { timeout: 20000 }).filter((i, o) => o.value !== '').should('have.length.greaterThan', 0)
    .first().then(($o) => cy.get(sel).select($o.val(), { force: true }))

/** The vehicle form's required fields: name, number, driver and owner with their mobiles. */
const fillVehicle = (n) => {
  cy.get('#vehicleName').type(n); cy.get('#vehicleNumber').type(n)
  cy.get('#vehicleDriverName').type('Guide Driver'); cy.get('#vehicleDriverMobile').type('03001234567')
  cy.get('#vehicleOwnerName').type('Guide Owner'); cy.get('#vehicleOwnerMobile').type('03007654321')
}

/** Add a class or a vehicle through its New form, exactly as a person does. */
const addOnScreen = (entity, fill) => {
  openList(entity)
  cy.get(`#new${entity}`, { timeout: 30000 }).click({ timeout: 30000 })
  cy.get(`#${entity}Modal`).should('have.class', 'open')
  // The form puts the cursor on its first field a frame after it opens — the campus picker. Wait for that, then
  // click the title so the picker is closed: otherwise it grabs the next keystrokes into its own search box.
  cy.focused({ timeout: 10000 }).should('have.attr', 'data-id', `${entity.toLowerCase()}SchoolDD`)
  cy.get(`#${entity}ModalTitle`).click()
  cy.get(`#${entity}Modal .bootstrap-select.open`).should('not.exist')
  fill()
  cy.intercept('POST', `**/add${entity}`).as(`add${entity}`)
  cy.get(`#add${entity}`).click()
  cy.wait(`@add${entity}`).its('response.body.status').should('eq', 'SUCCESS')
  cy.get(`#${entity}Modal`).should('not.have.class', 'open')
}

// ── console ────────────────────────────────────────────────────────────────────────────────────────────────
/** Run `code` in the page and yield the JSON the request it sends came back with. */
const runInPage = (code, method, path) => {
  const alias = `cmd${Math.floor(Math.random() * 1e9)}`
  cy.intercept(method, `**/${path}*`).as(alias)
  cy.window().then((w) => w.eval(code))
  return cy.wait(`@${alias}`).its('response.body')
}
/** A POST command, with the response printed the way a tester sees it in the console. */
const postCommand = (path, fields) =>
  `$.post(serverContext + '${path}', ${JSON.stringify(fields).replace(/"([a-zA-Z]+)":/g, '$1: ')}).done(function (r) { console.log(r.status, r.message) })`

/** Remove every row this run created, as whichever tenant now holds it (a red run moves rows). */
const sweep = () => {
  const del = (entity, field) => cy.request({ url: `/getUser${entity}`, failOnStatusCode: false }).then((r) => {
    const ids = list(r.body).filter((x) => String(x[field] || '').includes(TAG)).map((x) => x.id)
    if (ids.length) cy.request({ method: 'POST', url: `/delete${entity}`, form: true, body: { checked: ids.join(',') }, failOnStatusCode: false })
  })
  del('Student', 'name'); del('Grade', 'name'); del('Vehicle', 'number')
}

/**
 * The id a person reads off the grid's **Id** column — and proof it is the real one: it must equal the id the
 * server holds for that row, or the page would be teaching a tester to aim at the wrong record.
 */
const idOnScreen = (entity, field, value) => {
  openList(entity); search(entity, value); gridHas(entity, value)
  return cy.get(`#table${entity} tbody tr`).filter(`:contains("${value}")`).first().find('td').first().invoke('text').then((t) => {
    const shown = Number(String(t).trim())
    return cy.request(`/getUser${entity}`).then((r) => {
      const row = list(r.body).find((x) => x[field] === value)
      expect(row, `${entity} ${value} exists`).to.exist
      expect(shown, 'the Id column shows the server\'s id').to.eq(row.id)
      return shown
    })
  })
}

/** Victim's own row, read back through the same list the screen uses. */
const victimRow = (entity, field, value) => cy.request(`/getUser${entity}`).then((r) => {
  const row = list(r.body).find((x) => x[field] === value)
  expect(row, `${entity} ${value} exists for its owner`).to.exist
  return row
})

/** Ticks the row and deletes it with the grid's own Delete button. */
const deleteOnScreen = (entity, text) => {
  openList(entity)
  search(entity, text)
  gridHas(entity, text)
  cy.get(`#table${entity} tbody tr`).filter(`:contains("${text}")`).first().find('input[type="checkbox"]').check({ force: true })
  cy.intercept('POST', `**/delete${entity}`).as(`del${entity}`)
  cy.get(`#${SECTION[entity]} [onclick="confirmBulkDelete('${entity}')"]`).first().click({ force: true })
  cy.get('[data-ui-confirm="ok"]').click()
  cy.wait(`@del${entity}`)
  search(entity, text)
  gridLacks(entity, text)
}

describe('Test Book — EDU-IDOR-2, one school cannot touch another school\'s records (captured)', () => {
  beforeEach(() => cy.viewport(1366, 860))

  afterEach(function () {
    cur.passed = this.currentTest.state === 'passed'
    cur.capturedAt = new Date().toISOString()
    if (!cur.passed) cur.error = String((this.currentTest.err && this.currentTest.err.message) || '').slice(0, 400)
    cy.writeFile(`${OUT_DIR}/${cur.id}.json`, cur)
  })

  after(() => {
    asAttacker(); sweep()
    asVictim(); sweep()
  })

  // ── E1–E3: editing a row that belongs to another school ──────────────────────────────────────────────────
  const editCase = (id, entity, label, nameField, setup, attackFields, refusal) => {
    caseIt(id, `Editing another school's ${label} is refused, and the owner still has it`, () => {
      const name = `${TAG}_${id}_${label.replace(/\s+/g, '')}`
      testCase(id, `Editing another school's ${label} is refused — and the school that owns it still has it, unchanged`, {
        tenant: `${VICTIM} (the victim school) and ${ATTACKER} (a different school)`,
        role: 'Owner on both sides — the account the old check let straight through',
        purpose: `Before the fix, saving a ${label} with another school's id took that ${label} over: it was rewritten and moved into the caller's school. The edit form only ever opens your own rows, so the attack is a hand-sent request — the console step below.`,
        prereq: ['Two browser profiles (or one, signing out in between).', `No ${label} named like **${name}** exists in either school.`],
        data: [`${label[0].toUpperCase() + label.slice(1)} **${name}**`, 'Attacker\'s new name: **HIJACKED**'],
        rollback: `Cleanup deletes the test ${label} from the victim school with the grid's own Delete button. Nothing else is written.`,
      })
      SAFETY.push(() => {})

      const a1 = act(`Sign in as **${VICTIM}**. ${setup.how}`, setup.expect, setup.via ? { via: setup.via } : {})
      asVictim()
      setup.run(name)
      openList(entity); search(entity, name); gridHas(entity, name)
      snap(a1, 'victim-row')

      let victimId
      const a2 = act(`Still as the victim, with **${name}** searched in **${MENU[entity]}**, read the number in the **Id** column (the first column).`,
        ['One row is shown. **Write its Id down** — it is the record the attacker will aim at.'])
      idOnScreen(entity, nameField, name).then((id) => { victimId = id; a2.expect.push(`On this run the Id is **${id}**.`) })
      snap(a2, 'victim-id')

      const a3 = act(`Sign out and sign in as **${ATTACKER}** — a **different school**. Open **${MENU[entity]}** and search for **${name}**.`,
        [`**No matching records.** The ${label} belongs to another school, so this school's screen never lists it.`])
      asAttacker()
      openList(entity); search(entity, name); gridLacks(entity, name)
      snap(a3, 'attacker-cannot-see')

      const a4 = act(`Still as the attacker, send the edit by hand. Open **Console**, paste the command below with **the victim's id** in place of \`VICTIM_ID\`, and press **Enter**.`,
        [`The console prints **NOT_FOUND ${refusal}**.`, 'Before the fix it printed **SUCCESS** — and the row had just been moved into the attacker\'s school.'],
        { via: 'console', code: postCommand(`add${entity}`, attackFields('VICTIM_ID')) })
      cy.then(() => {
        const real = postCommand(`add${entity}`, attackFields(victimId))
        a4.code = postCommand(`add${entity}`, attackFields('VICTIM_ID'))
        runInPage(real, 'POST', `add${entity}`).then((body) => {
          expect(body.status, 'the edit is refused').to.eq('NOT_FOUND')
          expect(body.message).to.eq(refusal)
        })
      })
      openList(entity); search(entity, 'HIJACKED'); gridLacks(entity, `${TAG}_HIJACKED`)
      a4.expect.push('Search the attacker\'s own grid for **HIJACKED**: nothing — the row did not land here either.')
      snap(a4, 'attacker-refused')

      const a5 = act(`Sign back in as **${VICTIM}**, open **${MENU[entity]}** and search for **${name}**.`,
        [`The ${label} is **still there, under its original name**. Nothing about it changed.`])
      asVictim()
      openList(entity); search(entity, name); gridHas(entity, name)
      cy.get(`#table${entity} tbody`).should('not.contain', 'HIJACKED')
      victimRow(entity, nameField, name).then((row) => expect(row.id).to.eq(victimId))
      snap(a5, 'victim-still-has-it')

      const c1 = act(`Delete the test ${label}: still as the victim, tick its row in **${MENU[entity]}**, press **Delete**, and confirm.`,
        [`A dialog asks **Delete this record?**; after **Delete** the row is gone from the grid.`], { cleanup: true })
      deleteOnScreen(entity, name)
      snap(c1, 'deleted')
    })
  }

  editCase('E1', 'Student', 'student', 'name', {
    how: 'Add a student with the name below. *(The run records it through the same request the form sends; by hand: **Students → Manage Students → New Student**, fill Name, Enroll date, Campus, Class and Guardian, press **Submit**.)*',
    expect: ['The **Manage Students** grid lists the new student.'],
    via: 'run',
    run: (name) => cy.request({ method: 'POST', url: '/addStudent', form: true, body: { name, enrollNo: `EN${run}`, status: 'ACTIVE' } })
      .its('body.status').should('eq', 'SUCCESS'),
  }, (id) => ({ id, name: `${TAG}_HIJACKED`, status: 'ACTIVE' }), 'Student not found')

  editCase('E2', 'Grade', 'class', 'name', {
    how: 'Open **Academics → Classes**, press **New Class**, type the class name below, choose a **School**, and press **Submit**.',
    expect: ['The form closes and the **Classes** grid lists the new class.'],
    run: (name) => addOnScreen('Grade', () => { cy.get('#gradeName').type(name); pickFirst('#gradeSchoolDD') }),
  }, (id) => ({ id, name: `${TAG}_HIJACKED`, status: 'ACTIVE' }), 'Grade not found')

  editCase('E3', 'Vehicle', 'vehicle', 'number', {
    how: 'Open **School → Vehicles**, press **New Vehicle**, type the vehicle number below as both **Name** and **Number**, fill **Driver Name**, **Driver Mobile**, **Owner Name** and **Owner Mobile**, and press **Submit**.',
    expect: ['The form closes and the **Vehicles** grid lists the new vehicle.'],
    run: (name) => addOnScreen('Vehicle', () => { fillVehicle(name) }),
  }, (id) => ({ id, number: `${TAG}_HIJACKED`, name: `${TAG}_HIJACKED`, status: 'ACTIVE' }), 'Vehicle not found')

  // ── E4–E6: linking to, or filing under, another school's row ──────────────────────────────────────────────
  const linkCase = (id, title, purpose, target, attack, refusal) => {
    caseIt(id, title, () => {
      const name = `${TAG}_${id}`
      testCase(id, title, {
        tenant: `${VICTIM} (owns the target) and ${ATTACKER} (tries to use it)`,
        role: 'Owner on both sides',
        purpose,
        prereq: ['Two browser profiles (or one, signing out in between).'],
        data: [`Victim's ${target.label} **${name}_target**`, `Attacker's new record **${name}_attempt**`],
        rollback: `Cleanup deletes the victim's test ${target.label}. The attacker's record is never created, so there is nothing to remove on that side — action 4 checks it.`,
      })

      const a1 = act(`Sign in as **${VICTIM}**. ${target.how}`, target.expect, target.via ? { via: target.via } : {})
      asVictim()
      target.run(`${name}_target`)
      openList(target.entity); search(target.entity, `${name}_target`); gridHas(target.entity, `${name}_target`)
      snap(a1, 'victim-row')

      let targetId
      const a2 = act(`Still as the victim, with **${name}_target** searched in **${MENU[target.entity]}**, read the number in the **Id** column.`,
        ['One row is shown. **Write its Id down.**'])
      idOnScreen(target.entity, target.field, `${name}_target`).then((id) => { targetId = id; a2.expect.push(`On this run the Id is **${id}**.`) })
      snap(a2, 'victim-id')

      const a3 = act(`Sign out and sign in as **${ATTACKER}**. Send the request below with the victim's id in place of \`VICTIM_ID\`.`,
        [`The console prints **FAILED ${refusal}**.`, 'Before the fix it printed **SUCCESS** and the record was saved pointing at the other school.'],
        { via: 'console', code: postCommand(attack.path, attack.fields(name, 'VICTIM_ID')) })
      asAttacker()
      openList(attack.entity)
      cy.then(() => {
        a3.code = postCommand(attack.path, attack.fields(name, 'VICTIM_ID'))
        runInPage(postCommand(attack.path, attack.fields(name, targetId)), 'POST', attack.path).then((body) => {
          expect(body.status, 'refused').to.eq('FAILED')
          expect(body.message).to.eq(refusal)
        })
      })
      snap(a3, 'attacker-refused')

      const a4 = act(`Still as the attacker, open **${MENU[attack.entity]}** and search for **${name}_attempt**.`,
        ['**No matching records** — nothing was saved.'])
      openList(attack.entity); search(attack.entity, `${name}_attempt`); gridLacks(attack.entity, `${name}_attempt`)
      snap(a4, 'nothing-saved')

      const c1 = act(`Sign back in as **${VICTIM}** and delete the test ${target.label}: tick its row, press **Delete**, confirm.`,
        ['The row is gone from the grid.'], { cleanup: true })
      asVictim()
      deleteOnScreen(target.entity, `${name}_target`)
      snap(c1, 'deleted')
    })
  }

  const classTarget = {
    entity: 'Grade', label: 'class',
    how: 'Open **Academics → Classes**, press **New Class**, type the class name below, choose a **School**, and press **Submit**.',
    expect: ['The **Classes** grid lists the new class.'],
    run: (n) => addOnScreen('Grade', () => { cy.get('#gradeName').type(n); pickFirst('#gradeSchoolDD') }),
    field: 'name',
  }
  linkCase('E4', 'A student cannot be put in another school\'s class',
    'The class decides the fee: before the fix a student linked to another school\'s class was billed THAT school\'s fee, and its name printed on the voucher. The class picker only lists your own classes, so the attack is a hand-sent request.',
    classTarget,
    { entity: 'Student', path: 'addStudent', fields: (n, id) => ({ name: `${n}_attempt`, gradeId: id, status: 'ACTIVE' }) },
    'The selected class was not found.')

  linkCase('E5', 'A student cannot be given another school\'s vehicle',
    'Transport is charged by vehicle. The vehicle picker only lists your own vehicles, so the attack is a hand-sent request.',
    {
      entity: 'Vehicle', label: 'vehicle',
      how: 'Open **School → Vehicles**, press **New Vehicle**, type the number below as both **Name** and **Number**, fill the driver and owner names and mobiles, and press **Submit**.',
      expect: ['The **Vehicles** grid lists the new vehicle.'],
      run: (n) => addOnScreen('Vehicle', () => { fillVehicle(n) }),
      field: 'number',
    },
    { entity: 'Student', path: 'addStudent', fields: (n, id) => ({ name: `${n}_attempt`, vehicleId: id, status: 'ACTIVE' }) },
    'The selected vehicle was not found.')

  caseIt('E6', 'A class cannot be filed under another school\'s campus', () => {
    const name = `${TAG}_E6`
    testCase('E6', 'A class cannot be filed under another school\'s campus', {
      tenant: `${VICTIM} (owns the campus) and ${ATTACKER} (tries to file into it)`,
      role: 'Owner on both sides',
      purpose: 'A class (and a student, and a vehicle) is filed under a school campus. The School picker only lists your own campuses; before the fix a hand-sent id put the new class under another school\'s campus.',
      prereq: [`${VICTIM} has at least one school campus (Schools lists it).`],
      data: [`Attacker's class **${name}_attempt**`],
      rollback: 'Nothing is created on either side, so there is nothing to clean up — action 3 checks it.',
    })
    let schoolId
    const a1 = act(`Sign in as **${VICTIM}**, open **School → Campus**, and read the **Id** of the first campus listed.`,
      ['The grid lists this school’s campuses. **Write the first Id down.**'])
    asVictim()
    cy.openSection('SchoolDiv', '/educationDashboard')
    cy.get('#tableSchool tbody tr', { timeout: 20000 }).first().find('td').first().invoke('text').then((t) => {
      schoolId = Number(String(t).trim())
      return cy.request('/getUserSchool').then((r) => {
        expect(list(r.body).map((x) => x.id), 'the Id on screen is one of this school’s campuses').to.include(schoolId)
        a1.expect.push(`On this run the first Id is **${schoolId}**.`)
      })
    })
    snap(a1, 'victim-campus')

    const fields = (id) => ({ name: `${name}_attempt`, schoolId: id, status: 'ACTIVE' })
    const a2 = act(`Sign out, sign in as **${ATTACKER}**, and send the request below with that id in place of \`VICTIM_SCHOOL_ID\`.`,
      ['The console prints **FAILED You do not have access to that branch.**', 'Before the fix it printed **SUCCESS**.'],
      { via: 'console', code: postCommand('addGrade', fields('VICTIM_SCHOOL_ID')) })
    asAttacker()
    openList('Grade')
    cy.then(() => runInPage(postCommand('addGrade', fields(schoolId)), 'POST', 'addGrade').then((body) => {
      expect(body.status).to.eq('FAILED')
      expect(body.message).to.eq('You do not have access to that branch.')
    }))
    snap(a2, 'attacker-refused')

    const a3 = act(`Still as the attacker, open **Academics → Classes** and search for **${name}_attempt**.`, ['**No matching records** — nothing was saved.'])
    openList('Grade'); search('Grade', `${name}_attempt`); gridLacks('Grade', `${name}_attempt`)
    snap(a3, 'nothing-saved')
  })

  // ── E7: the control — your own records still save, link and edit ─────────────────────────────────────────
  caseIt('E7', 'CONTROL: your own class, pupil and edit still work', () => {
    const cls = `${TAG}_E7_class`
    const pupil = `${TAG}_E7_pupil`
    testCase('E7', 'CONTROL — your own class, a pupil in it, and an edit of that pupil all still work', {
      tenant: ATTACKER, role: 'Owner',
      purpose: 'A fix that refused everything would pass E1–E6. This case runs the SAME kind of requests on your own school\'s records and expects them to succeed.',
      prereq: [`Signed in as ${ATTACKER}.`],
      data: [`Class **${cls}**`, `Pupil **${pupil}**, renamed to **${pupil}_renamed**`],
      rollback: 'Cleanup deletes the pupil and the class with the grids\' own Delete buttons.',
    })
    asAttacker()
    const a1 = act('Open **Academics → Classes**, press **New Class**, type the class name below, choose a **School**, and press **Submit**.',
      ['The form closes and the **Classes** grid lists the new class.'])
    addOnScreen('Grade', () => { cy.get('#gradeName').type(cls); pickFirst('#gradeSchoolDD') })
    openList('Grade'); search('Grade', cls); gridHas('Grade', cls)
    snap(a1, 'own-class')

    let classId, pupilId
    const a2 = act('With the class searched in **Academics → Classes**, read the number in its **Id** column.', ['**Write it down.**'])
    idOnScreen('Grade', 'name', cls).then((id) => { classId = id; a2.expect.push(`On this run the Id is **${id}**.`) })
    snap(a2, 'own-class-id')

    const addFields = (id) => ({ name: pupil, gradeId: id, status: 'ACTIVE' })
    const a3 = act('Add a pupil in that class with the command below (your own class id in place of `CLASS_ID`).',
      ['The console prints **SUCCESS**.', `The **Manage Students** grid lists **${pupil}**.`], { via: 'console', code: postCommand('addStudent', addFields('CLASS_ID')) })
    cy.then(() => runInPage(postCommand('addStudent', addFields(classId)), 'POST', 'addStudent').its('status').should('eq', 'SUCCESS'))
    openList('Student'); search('Student', pupil); gridHas('Student', pupil)
    snap(a3, 'own-pupil')

    const editFields = (id, cid) => ({ id, name: `${pupil}_renamed`, gradeId: cid, status: 'ACTIVE' })
    const a4 = act('Edit that pupil — the same edit request E1 sent at another school. Read the pupil’s **Id** in **Students → Manage Students**, then send the command below with both ids filled in.',
      ['The console prints **SUCCESS**.', `The **Manage Students** grid now lists **${pupil}_renamed**.`], { via: 'console', code: postCommand('addStudent', editFields('PUPIL_ID', 'CLASS_ID')) })
    idOnScreen('Student', 'name', pupil).then((id) => { pupilId = id })
    cy.then(() => runInPage(postCommand('addStudent', editFields(pupilId, classId)), 'POST', 'addStudent').its('status').should('eq', 'SUCCESS'))
    openList('Student'); search('Student', `${pupil}_renamed`); gridHas('Student', `${pupil}_renamed`)
    snap(a4, 'own-pupil-renamed')

    const c1 = act('Delete the pupil, then the class: tick each row, press **Delete**, confirm.', ['Both rows are gone from their grids.'], { cleanup: true })
    deleteOnScreen('Student', `${pupil}_renamed`)
    deleteOnScreen('Grade', cls)
    snap(c1, 'deleted')
  })
})
