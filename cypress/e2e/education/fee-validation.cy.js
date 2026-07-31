/**
 * Slice B — fee collection validation (audit finding B).
 * Design: microservices/docs/slices/edu-B-fee-validation.md
 *
 * `addFc` is the money-in endpoint: what it accepts flows to the shared subledger and posts to a GL that
 * three other verticals read. Before this slice it accepted negative amounts, and would raise a DUE
 * against a student who does not exist — a permanent, uncollectable debit no screen could explain.
 *
 * The rule MATRIX (negatives, amount-vs-percentage discounts, due-day bounds) lives in FeeValidatorTest,
 * pure, on `mvn test`. What is asserted here is what a unit test cannot: that the guard is wired in
 * ahead of the write, and that nothing reaches the ledger when it refuses.
 *
 * Requires education-service + finance-service + gateway up. Run headed.
 */
const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
const rows = (body) => {
  const b = parse(body) || {}
  if (Array.isArray(b.collection)) return b.collection
  const k = Object.keys(b).find((x) => Array.isArray(b[x]))
  return k ? b[k] : []
}

const addFc = (body) =>
  cy.request({ method: 'POST', url: '/addFc', form: true, failOnStatusCode: false, body })

const seedStudent = (enrollNo, gradeId) =>
  cy.request({
    method: 'POST', url: '/addStudent', form: true, failOnStatusCode: false,
    body: { name: 'FV ' + enrollNo, enrollNo, status: 'ACTIVE', ...(gradeId ? { gradeId } : {}) },
  }).then((r) => expect(JSON.stringify(r.body), `addStudent ${enrollNo}`).to.match(/SUCCESS/))

const refused = (r, label) => {
  const b = parse(r.body)
  expect(b.status, `${label}: ${JSON.stringify(b)}`).to.not.eq('SUCCESS')
  return b
}

describe('Education — fee collection validation (finding B)', () => {
  beforeEach(() => { cy.loginAsEduOwner() })

  it('a negative payment is refused, and the message names the field and the value', () => {
    const en = 'FVN' + Date.now()
    seedStudent(en)
    addFc({ enrollNo: en, fee: 3000, dueAmount: 3000, feePaid: -100, receivedIn: 'Cash' }).then((r) => {
      const b = refused(r, 'negative feePaid')
      expect(b.message).to.contain('Fee paid').and.to.contain('-100')
    })
  })

  it('a refused collection reaches NEITHER the ledger NOR the fee list', () => {
    // The point of validating before the write: a negative feePaid would otherwise settle through the
    // shared subledger and post a negative cash receipt into the GL.
    const en = 'FVL' + Date.now()
    seedStudent(en)
    cy.request({ method: 'POST', url: '/gl/ensureDefaults', failOnStatusCode: false })
    cy.request('/gl/trialBalance').then((tb) => {
      const before = parse(tb.body)
      const cashBefore = (before.rows || []).find((x) => x.code === '1000') || { debit: 0, credit: 0 }
      const netBefore = Number(cashBefore.debit) - Number(cashBefore.credit)

      addFc({ enrollNo: en, fee: 3000, dueAmount: 3000, feePaid: -500, receivedIn: 'Cash' })
        .then((r) => refused(r, 'negative feePaid'))

      cy.wait(1200)
      cy.request('/gl/trialBalance').then((tb2) => {
        const after = parse(tb2.body)
        const cashAfter = (after.rows || []).find((x) => x.code === '1000') || { debit: 0, credit: 0 }
        expect(Number(cashAfter.debit) - Number(cashAfter.credit), 'Cash untouched by a refused collection')
          .to.eq(netBefore)
        expect(after.balanced, 'and the GL is still balanced').to.eq(true)
      })
      // No fee row was created either — a refusal must not half-write.
      cy.request(`/loadFL?enrollNo=${en}`).then((r) => {
        expect(rows(r.body).length, 'no fee row persisted for a refused collection').to.eq(0)
      })
    })
  })

  it('EVERY problem comes back at once, not one per round trip', () => {
    const en = 'FVM' + Date.now()
    seedStudent(en)
    addFc({ enrollNo: en, fee: 3000, dueAmount: -1, feePaid: -100, vehicleFee: -7, receivedIn: 'Cash' })
      .then((r) => {
        const b = refused(r, 'several bad fields')
        expect(b.message).to.contain('Fee paid')
        expect(b.message).to.contain('Due amount')
        expect(b.message).to.contain('Vehicle fee')
      })
  })

  it('an AMOUNT discount above the fee is refused', () => {
    const en = 'FVD' + Date.now()
    seedStudent(en)
    addFc({
      enrollNo: en, fee: 3000, dueAmount: 3000, feePaid: 0,
      discount: 5000, discountType: 'amount', receivedIn: 'Cash',
    }).then((r) => {
      const b = refused(r, 'discount above fee')
      expect(b.message).to.contain('5000').and.to.contain('3000')
    })
  })

  it('a PERCENTAGE discount is bounded by 100, not by the fee', () => {
    // The domain distinction: 10% on a fee of 5 is normal; comparing it against the fee would reject it.
    const en = 'FVP' + Date.now()
    seedStudent(en)
    addFc({
      enrollNo: en, fee: 5, dueAmount: 5, feePaid: 0,
      discount: 10, discountType: '%', receivedIn: 'Cash',
    }).then((r) => {
      expect(parse(r.body).status, `10% of a small fee is legitimate: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    })

    const en2 = 'FVP2' + Date.now()
    seedStudent(en2)
    addFc({
      enrollNo: en2, fee: 3000, dueAmount: 3000, feePaid: 0,
      discount: 150, discountType: '%', receivedIn: 'Cash',
    }).then((r) => refused(r, '150% discount'))
  })

  // ── B2: a DUE needs a real student, not only a payment ──────────────────────────────────────

  it('a DUE against an unknown enrolment is refused — the gap this slice closes (B2)', () => {
    // Before slice B this saved: feePaid was 0, so the 0.2a guard (tendered > 0) never fired, and the row
    // then sat in arrears and aging forever against a student nobody could find.
    addFc({ enrollNo: 'GHOST' + Date.now(), fee: 3000, dueAmount: 3000, feePaid: 0, receivedIn: 'Cash' })
      .then((r) => {
        const b = refused(r, 'due against a phantom student')
        expect(b.message).to.match(/no student found/i)
      })
  })

  it('the same due against a REAL student is saved', () => {
    const en = 'FVR' + Date.now()
    seedStudent(en)
    addFc({ enrollNo: en, fee: 3000, dueAmount: 3000, feePaid: 0, receivedIn: 'Cash' })
      .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))
  })

  it('a zero-value collection is still accepted — it is not a charging row', () => {
    // Regression guard: fees-to-gl asserts a zero collection is "not an accounting event", so the new
    // student check must not start refusing it.
    const en = 'FVZ' + Date.now()
    seedStudent(en)
    addFc({ enrollNo: en, fee: 0, dueAmount: 0, feePaid: 0, receivedIn: 'Cash' })
      .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))
  })

  it('a normal collection is unaffected — the guard must not break the happy path', () => {
    const en = 'FVOK' + Date.now()
    seedStudent(en)
    addFc({ enrollNo: en, fee: 2000, dueAmount: 2000, feePaid: 2000, receivedIn: 'Cash', payee: 'CyParent' })
      .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))
  })

  // ── B3: no zero opening-due row ─────────────────────────────────────────────────────────────
  //
  // registerOpeningDue only runs when the org's `autoRegisterDues` policy is ON. Without setting it,
  // BOTH tests below pass for the wrong reason — no row appears because the feature is off entirely,
  // not because of the B3 guard. So the policy is enabled explicitly and RESTORED afterwards, since it
  // is org-wide and would otherwise change what every later spec sees.

  const setAutoRegister = (on) =>
    cy.request({
      method: 'POST', url: '/saveFeeSetting', form: true, failOnStatusCode: false,
      body: { autoRegisterDues: on ? 'true' : 'false' },
    }).then((r) => expect(parse(r.body).status, `saveFeeSetting: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))

  const withAutoRegister = (fn) =>
    cy.request('/getFeeSetting').then((r) => {
      const prev = (parse(r.body) || {}).object || {}
      const was = prev.autoRegisterDues === true
      setAutoRegister(true)
      fn()
      // Restore, so enabling it here cannot leak into fees-ar / fees-to-gl row counts.
      cy.then(() => setAutoRegister(was))
    })

  const makeGrade = (name, fee) =>
    cy.request({ method: 'POST', url: '/addGrade', form: true, failOnStatusCode: false,
      body: { name, fee, status: 'Active' } })
      .then((r) => expect(parse(r.body).status, `addGrade: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))
      .then(() => cy.request('/getUserGrade'))
      .then((r) => {
        const g = rows(r.body).find((x) => x.name === name)
        expect(g, `class "${name}" exists`).to.exist
        return cy.wrap(g.id, { log: false })
      })

  it('registering a student into a class with NO fee creates no opening-due row (B3)', () => {
    const stamp = Date.now()
    withAutoRegister(() => {
      // A zero-fee class — the case that used to write fee=0, dueAmount=0, receivedIn=OPENING_DUE.
      makeGrade('FVG' + stamp, 0).then((gradeId) => {
        const en = 'FVNOFEE' + stamp
        seedStudent(en, gradeId)
        cy.request(`/loadFL?enrollNo=${en}`).then((fl) => {
          expect(rows(fl.body).length, 'no placeholder opening-due row').to.eq(0)
        })
      })
    })
  })

  // ── §8: the other money-carrying forms ──────────────────────────────────────────────────────

  it('a negative CLASS FEE is refused — it would reach every student in the class', () => {
    cy.request({ method: 'POST', url: '/addGrade', form: true, failOnStatusCode: false,
      body: { name: 'FVNEG' + Date.now(), fee: -1, status: 'Active' } })
      .then((r) => {
        const b = refused(r, 'negative class fee')
        expect(b.message).to.contain('Class fee')
      })
  })

  it('a FREE class is allowed — zero is legitimate', () => {
    cy.request({ method: 'POST', url: '/addGrade', form: true, failOnStatusCode: false,
      body: { name: 'FVFREE' + Date.now(), fee: 0, status: 'Active' } })
      .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))
  })

  it('a PERCENTAGE discount above 100% is refused', () => {
    // discountAmount() computes base * amount / 100, and monthlyDue floors the result at 0 — so without
    // this the parent is silently billed nothing.
    cy.request({ method: 'POST', url: '/addDiscount', form: true, failOnStatusCode: false,
      body: { name: 'FVD150' + Date.now(), di: '%', amount: 150, status: 'Active' } })
      .then((r) => {
        const b = refused(r, '150% discount')
        expect(b.message).to.contain('150')
      })
  })

  it('a 100% discount is allowed, and so is a large AMOUNT discount', () => {
    const stamp = Date.now()
    cy.request({ method: 'POST', url: '/addDiscount', form: true, failOnStatusCode: false,
      body: { name: 'FVD100' + stamp, di: '%', amount: 100, status: 'Active' } })
      .then((r) => expect(parse(r.body).status, `full scholarship: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))
    // No fee in context bounds an AMOUNT discount, so a large one must not be refused.
    cy.request({ method: 'POST', url: '/addDiscount', form: true, failOnStatusCode: false,
      body: { name: 'FVDBIG' + stamp, di: 'amount', amount: 50000, status: 'Active' } })
      .then((r) => expect(parse(r.body).status, JSON.stringify(r.body)).to.eq('SUCCESS'))
  })

  it('a negative discount amount is refused', () => {
    cy.request({ method: 'POST', url: '/addDiscount', form: true, failOnStatusCode: false,
      body: { name: 'FVDNEG' + Date.now(), di: 'amount', amount: -5, status: 'Active' } })
      .then((r) => refused(r, 'negative discount'))
  })

  it('registering into a class WITH a fee still creates the opening due', () => {
    // The other half of B3: the guard must suppress only the EMPTY row, never a real due.
    const stamp = Date.now()
    withAutoRegister(() => {
      makeGrade('FVGF' + stamp, 1500).then((gradeId) => {
        const en = 'FVFEE' + stamp
        seedStudent(en, gradeId)
        cy.request(`/loadFL?enrollNo=${en}`).then((fl) => {
          const due = rows(fl.body)
          expect(due.length, 'the opening due is still raised').to.be.greaterThan(0)
          expect(due[0].dueAmount, 'and it carries the class fee, not zero').to.eq(1500)
        })
      })
    })
  })
})
