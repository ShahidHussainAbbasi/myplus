/**
 * HMS RELINK (client, 2026-10-12) — on the clinic's tables:
 *   RL-01  Today's queue → Relink: the patient who has not been seen goes to another doctor, the desk's own phone search
 *          prefilled; the old token reads "Moved to Dr … as …", the new doctor's token is waiting.
 *   RL-02  Relink also works where the clinic gives ONE token per patient per day (cancel and new token in one step).
 *   RL-03  A doctor who cannot take the patient (closed today) refuses the relink — and the old token is UNCHANGED
 *          (the cancel is rolled back with the refusal: the patient is never left without a token).
 *   RL-04  Cancel asks WHY; an empty reason is refused; the reason shows on the board.
 *   RL-05  Once the visit has started: no Relink, Cancel disabled; the server refuses both.
 *   RL-06  Patients → Edit corrects name, CNIC, date of birth and sex inline; a stale edit is refused.
 *
 * Tenant: owner.pharma@ (clinic on). Doctors: "Dr Ahmed (gate)" and "Dr Sana (gate)" (shared HMS fixtures).
 * Cleanup (after): every token of this run cancelled or completed, the started visit completed, Dr Sana's day reset,
 * the one-token-per-day setting reset, the patients retired, the clinic switch reset.
 *
 *   npx cypress run --browser chrome --spec "cypress/e2e/hms/hms-relink-cancel-edit.cy.js"
 */
describe('HMS RELINK — move a waiting patient, cancel with a reason, edit a patient', () => {
  const OWNER = 'owner.pharma@myplus.com'
  const PW = 'Demo@2025!'
  const run = String(Date.now()).slice(-7)
  let seq = 0
  const newPhone = () => '036' + run.slice(-6) + String(seq++ % 10) + String(Math.floor(Math.random() * 10))
  const created = { patients: [], tokens: [], visits: [] }
  const doc = {}
  const shot = (n) => cy.screenshot(`hms-relink/${n}`, { capture: 'viewport', overwrite: true })
  const relogin = () => cy.loginAs(OWNER, PW, '/getBusinessDashboardStats', `hms-rl-${run}`)

  const doctors = () => cy.request('/clinic/doctors').its('body.data')
  const findOrAddDoctor = (name) => doctors().then((list) => list.find((d) => d.name === name)
    || cy.postJson('/clinic/doctors', { name, speciality: 'General medicine', dailyLimit: null }).its('body.data'))
  // a CNIC on every test patient: valid whether or not the clinic requires one (that setting is the clinic's, not ours)
  const newCnic = () => '35202-' + String(Date.now()).slice(-7) + '-' + String(seq % 10)
  const newPatient = (label) => cy.postJson('/clinic/patients', { phone: newPhone(), name: `RL ${label} ${run}`, cnic: newCnic() }).then((r) => {
    expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
    created.patients.push(r.body.data.id)
    return r.body.data
  })
  const issue = (patientId, providerId) => cy.postJson('/clinic/tokens', { patientId, providerId }).then((r) => {
    expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
    created.tokens.push(r.body.data.id)
    return r.body.data
  })
  const token = (id) => cy.request(`/clinic/tokens/${id}`).its('body.data')
  const openBoard = () => {
    cy.visit('/clinicDashboard')
    cy.get('#clinNavQueue').click()   // not [onclick*="QueueDiv"]: My queue's "MyQueueDiv" matches that too
    cy.get('#QueueDiv').should('be.visible')
  }
  // by the TOKEN cell: a moved token's row also names the new number ("Moved to … as B-002")
  const boardRow = (label) => cy.contains('#clinQueueBoard td.clin-token-cell', new RegExp('^' + label + '$'), { timeout: 15000 }).parent('tr')

  before(() => {
    cy.task('clearDemoCaps')
    cy.loginAsOperator()
    cy.setEntitlement(OWNER, 'clinic', 'ACTIVE', 'hms relink gate')
    relogin()
    cy.setCapability('clinic', true)
    findOrAddDoctor('Dr Ahmed (gate)').then((d) => { doc.ahmed = d })
    findOrAddDoctor('Dr Sana (gate)').then((d) => { doc.sana = d })
  })

  beforeEach(() => relogin())

  after(() => {
    relogin()
    cy.then(() => created.tokens.forEach((id) => cy.request(`/clinic/tokens/${id}`).its('body.data').then((t) => {
      if (!t) return
      if (t.status === 'WAITING' || t.status === 'CALLED') cy.postJson(`/clinic/tokens/${id}/cancel`, { reason: 'cypress cleanup' })
      if (t.status === 'IN_CONSULTATION') cy.postJson(`/clinic/tokens/${id}/complete`)
    })))
    cy.then(() => { if (doc.sana) cy.postJson('/clinic/doctors/day', { providerId: doc.sana.id, reset: true }) })
    cy.request({ method: 'POST', url: '/clinic/settings/reset?key=clinic.queue.multiDoctorPerDay', failOnStatusCode: false })
    cy.then(() => created.patients.forEach((id) => cy.postJson(`/clinic/patients/${id}/retire`, { reason: 'cypress cleanup' })))
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.clinic' }, failOnStatusCode: false })
  })

  it('RL-01 Relink sends a waiting patient to another doctor through the desk\'s own search', () => {
    newPatient('Move').then((p) => issue(p.id, doc.ahmed.id).then((t) => {
      openBoard()
      boardRow(t.tokenLabel).find('.clin-relink').should('be.visible')
      shot('RL-01-1-board-relink-button')
      boardRow(t.tokenLabel).find('.clin-relink').click()
      // the Reception screen, the phone already searched, the patient already chosen
      cy.get('#ReceptionDiv').should('be.visible')
      cy.get('#clinPatPhone').should('have.value', p.phone)
      cy.get('#clinRelinkNote').should('be.visible').and('contain.text', t.tokenLabel).and('contain.text', 'Dr Ahmed (gate)')
      cy.get(`#clinDoctor option[value="${doc.ahmed.id}"]`).should('be.disabled')
      cy.get('#clinIssueTokenText').should('have.text', 'Move to this doctor')
      shot('RL-01-2-reception-prefilled')
      cy.intercept('POST', '**/clinic/tokens/*/relink').as('relink')
      cy.get('#clinDoctor').select(String(doc.sana.id), { force: true })
      cy.get('#clinIssueToken').click()
      cy.wait('@relink').its('response.body').then((b) => {
        expect(b.success, JSON.stringify(b)).to.eq(true)
        created.tokens.push(b.data.id)
        expect(b.data.providerName).to.eq('Dr Sana (gate)')
        cy.get('#clinTokenNo').should('have.text', b.data.tokenLabel)
        shot('RL-01-3-new-token-slip')
        token(t.id).then((old) => {
          expect(old.status, 'the old token').to.eq('CANCELLED')
          expect(old.cancelReason).to.eq(`Moved to Dr Sana (gate) as ${b.data.tokenLabel}`)
        })
        cy.get('#clinRelinkNote').should('not.be.visible')
        openBoard()
        boardRow(t.tokenLabel).should('contain.text', `Moved to Dr Sana (gate) as ${b.data.tokenLabel}`)
        boardRow(b.data.tokenLabel).should('contain.text', 'Dr Sana (gate)').and('contain.text', 'Waiting')
        shot('RL-01-4-board-old-moved-new-waiting')
      })
    }))
  })

  it('RL-02 Relink works where the clinic gives one token per patient per day', () => {
    cy.request({ method: 'POST', url: '/clinic/settings?key=clinic.queue.multiDoctorPerDay&value=false' })
    newPatient('OnePerDay').then((p) => issue(p.id, doc.ahmed.id).then((t) => {
      cy.postJson(`/clinic/tokens/${t.id}/relink`, { providerId: doc.sana.id }).its('body').then((b) => {
        expect(b.success, `one token a day: ${JSON.stringify(b)}`).to.eq(true)
        created.tokens.push(b.data.id)
        expect(b.data.providerName).to.eq('Dr Sana (gate)')
      })
      token(t.id).its('status').should('eq', 'CANCELLED')
    }))
    cy.request({ method: 'POST', url: '/clinic/settings/reset?key=clinic.queue.multiDoctorPerDay' })
  })

  it('RL-03 A doctor who cannot take the patient refuses — and the old token is left as it was', () => {
    cy.postJson('/clinic/doctors/day', { providerId: doc.sana.id, closed: true }).its('body.success').should('eq', true)
    newPatient('Closed').then((p) => issue(p.id, doc.ahmed.id).then((t) => {
      cy.postJson(`/clinic/tokens/${t.id}/relink`, { providerId: doc.sana.id }).its('body').then((b) => {
        expect(b.success).to.eq(false)
        expect(b.message).to.contain('is not seeing patients today')
      })
      // the cancel was rolled back with the refusal: the patient still holds their token, still waiting
      token(t.id).then((now) => {
        expect(now.status, 'rolled back').to.eq('WAITING')
        expect(now.cancelReason || null).to.eq(null)
      })
    }))
    cy.postJson('/clinic/doctors/day', { providerId: doc.sana.id, reset: true })
  })

  it('RL-04 Cancel asks why; an empty reason is refused; the reason shows on the board', () => {
    newPatient('Cancel').then((p) => issue(p.id, doc.ahmed.id).then((t) => {
      openBoard()
      boardRow(t.tokenLabel).find('.clin-move[data-action="cancel"]').click()
      cy.get('.uiC-input').should('be.visible')
      shot('RL-04-1-cancel-asks-why')
      cy.get('[data-ui-confirm="ok"]').click()
      cy.get('.uiC-card').should('contain.text', 'Say why')   // required: the dialog stays, naming what is missing
      cy.get('.uiC-input').type('Patient asked to cancel')
      cy.get('[data-ui-confirm="ok"]').click()
      boardRow(t.tokenLabel).should('contain.text', 'Cancelled').and('contain.text', 'Patient asked to cancel')
      shot('RL-04-2-cancelled-with-reason')
      // the server asks too: no reason, no cancel
      newPatient('Cancel2').then((p2) => issue(p2.id, doc.ahmed.id).then((t2) => {
        cy.postJson(`/clinic/tokens/${t2.id}/cancel`, {}).its('body.message').should('contain', 'Say why the token is cancelled')
        token(t2.id).its('status').should('eq', 'WAITING')
      }))
    }))
  })

  it('RL-05 Once the visit has started there is no Relink and Cancel is disabled; the server refuses both', () => {
    newPatient('Started').then((p) => issue(p.id, doc.ahmed.id).then((t) => {
      cy.postJson(`/clinic/tokens/${t.id}/call`)
      cy.postJson(`/clinic/consult/tokens/${t.id}/start`).its('body.success').should('eq', true)
      openBoard()
      boardRow(t.tokenLabel).find('.clin-relink').should('not.exist')
      boardRow(t.tokenLabel).find('button.btn-danger').should('be.disabled')
      shot('RL-05-1-visit-started-cancel-disabled')
      cy.postJson(`/clinic/tokens/${t.id}/relink`, { providerId: doc.sana.id }).its('body.message')
        .should('contain', 'only a patient who has not been seen yet')
      cy.postJson(`/clinic/tokens/${t.id}/cancel`, { reason: 'try' }).its('body.message').should('contain', 'it cannot be cancelled')
    }))
  })

  it('RL-06 Patients → Edit corrects the details inline; a stale edit is refused', () => {
    newPatient('Edit').then((p) => {
      cy.intercept('GET', '**/clinic/patients?q=*').as('search')
      cy.visit('/clinicDashboard')
      // the list reloads on every keystroke: paste the whole name at once and wait for THAT search, so no late reload
      // redraws the table under the open edit form
      cy.get('#clinPatSearch').invoke('val', p.name).trigger('input')
      cy.wait('@search')
      cy.contains('#clinPatBody tr', p.name, { timeout: 15000 }).find('.clin-pat-edit-btn').click()
      cy.get('.clin-pat-edit-row').should('be.visible')
      shot('RL-06-1-inline-edit-open')
      cy.get('.clin-ed-name').clear().type(`RL Edited ${run}`)
      cy.get('.clin-ed-cnic').clear().type(`35202-${run}-1`)
      cy.get('.clin-ed-dob').clear().type('1990-05-17')
      cy.get('.clin-ed-sex').select('F', { force: true })
      cy.intercept('PUT', '**/clinic/patients/*').as('save')
      cy.get('.clin-ed-save').click()
      cy.wait('@save').its('response.body.success').should('eq', true)
      cy.get('#clinPatSearch').invoke('val', `RL Edited ${run}`).trigger('input')
      cy.wait('@search')
      cy.contains('#clinPatBody tr', `RL Edited ${run}`, { timeout: 15000 }).should('be.visible')
      shot('RL-06-2-saved')
      cy.request(`/clinic/patients/${p.id}`).its('body.data').then((now) => {
        expect(now.name).to.eq(`RL Edited ${run}`)
        expect(now.cnic).to.eq(`35202-${run}-1`)
        expect(now.dateOfBirth).to.eq('1990-05-17')
        expect(now.sex).to.eq('F')
        // the version the form loaded is now old: a second edit with it is refused, never silently overwritten
        cy.request({ method: 'PUT', url: `/clinic/patients/${p.id}`, body: { name: 'Stale', version: p.version }, failOnStatusCode: false })
          .its('body.success').should('eq', false)
      })
    })
  })
})
