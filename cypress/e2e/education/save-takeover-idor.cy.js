/**
 * Cross-tenant SAVE takeover — the regression gate for education review finding A.
 *
 * Sibling of delete-idor.cy.js, and a strictly worse bug than the one that spec covers. Every education
 * addX endpoint resolved an edit by a client-supplied id with NO ownership check, and then stamped
 * organizationId with the CALLER's org:
 *
 *     Guardian obj = (dto.getId() != null)
 *             ? guardianRepository.findById(dto.getId()).orElseGet(Guardian::new)   // unscoped
 *             : new Guardian();
 *     obj.setOrganizationId(orgId);                                                 // takes the row
 *
 * So the attacker did not merely EDIT another school's record — the save MOVED it into their own tenant.
 * The victim's row then vanished from every scoped query they run: a silent cross-tenant takeover, with
 * sequential ids making the targets trivially enumerable.
 *
 * The attack below is the real one: tenant B posts tenant A's row id to addX. The fix must make that a
 * NOT_FOUND, leave the row in A's org, and leave A's field values untouched.
 *
 * Tenants: owner.education@ and demo.education@ — both legitimate education logins in different seeded
 * orgs. As with the delete gate, this was never about privilege level; it was a missing tenant check.
 *
 * Run headed:
 *   npx cypress open --e2e        (pick education/save-takeover-idor.cy.js)
 *   npx cypress run  --spec cypress/e2e/education/save-takeover-idor.cy.js
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const rows = (body) => {
  for (const key of ['collection', 'object', 'data']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}

const post = (url, body) =>
  cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })

// cy.wrap(... || null), never a bare `find`: a .then() returning undefined makes Cypress yield the
// PREVIOUS subject, so an assertion would silently run against the Response object.
const findBy = (listUrl, predicate) =>
  cy.request({ url: listUrl, failOnStatusCode: false })
    .then((r) => cy.wrap(rows(r.body).find(predicate) || null))

describe('Security: a tenant cannot take over another tenant\'s record by saving over its id', () => {

  /**
   * One case per vulnerable entity. Each declares how to create a row, how to find it, the field the
   * attacker tries to overwrite, and the list endpoint — the attack itself is identical for all of them,
   * which is exactly why the bug was repeated seven times.
   */
  const CASES = [
    {
      name: 'Guardian',
      addUrl: '/addGuardian',
      listUrl: '/getUserGuardian',
      create: (tag) => ({ name: tag, cnic: `CN${uniq()}`, status: 'Active' }),
      match: (tag) => (x) => x.name === tag,
    },
    {
      name: 'Staff',
      addUrl: '/addStaff',
      listUrl: '/getUserStaff',
      create: (tag) => ({ name: tag, designation: 'Teacher', status: 'Active' }),
      match: (tag) => (x) => x.name === tag,
    },
    {
      name: 'Subject',
      addUrl: '/addSubject',
      listUrl: '/getUserSubject',
      create: (tag) => ({ name: tag, code: `SC${uniq()}`, status: 'Active' }),
      match: (tag) => (x) => x.name === tag,
    },
    {
      name: 'Discount',
      addUrl: '/addDiscount',
      listUrl: '/getUserDiscount',
      create: (tag) => ({ name: tag, di: 'Percent', amount: 10, status: 'Active' }),
      match: (tag) => (x) => x.name === tag,
    },
    {
      name: 'Owner',
      addUrl: '/addOwner',
      listUrl: '/getUserOwner',
      create: (tag) => ({ name: tag, mobile: '03001234567', address: 'X', status: 'Active' }),
      match: (tag) => (x) => x.name === tag,
    },
    {
      name: 'School',
      addUrl: '/addSchool',
      listUrl: '/getUserSchool',
      create: (tag) => ({ name: tag, branchName: tag, status: 'Active' }),
      match: (tag) => (x) => x.name === tag || x.branchName === tag,
    },
  ]

  CASES.forEach((c) => {
    describe(c.name, () => {
      const victimTag = `CY_VICTIM_${c.name}_${uniq()}`
      const stolenTag = `CY_STOLEN_${c.name}_${uniq()}`
      let victimId = null

      before(() => {
        // ── Tenant A creates a row it owns.
        cy.loginAsEduOwner()
        post(c.addUrl, c.create(victimTag)).then((r) => {
          expect(JSON.stringify(r.body), `${c.name} created by tenant A`).to.match(/SUCCESS/)
        })
        findBy(c.listUrl, c.match(victimTag)).then((row) => {
          expect(row, `${c.name} is readable by its owner`).to.not.be.null
          victimId = row.id != null ? row.id : row[Object.keys(row).find((k) => /id$/i.test(k))]
          expect(victimId, `${c.name} id captured`).to.exist
        })
      })

      it('tenant B cannot overwrite it, and cannot move it into their own org', () => {
        // ── Tenant B posts tenant A's id. This is the takeover attempt.
        cy.loginAsEducation()
        cy.then(() => {
          const attack = c.create(stolenTag)
          attack.id = victimId
          return post(c.addUrl, attack)
        }).then((r) => {
          // Must be refused. NOT_FOUND is the right answer: it neither confirms nor denies that the id
          // exists in another tenant.
          expect(JSON.stringify(r.body), `${c.name} takeover must be refused`).to.not.match(/SUCCESS/)
        })

        // ── The attacker must not see the row in their own tenant.
        findBy(c.listUrl, c.match(stolenTag)).then((row) => {
          expect(row, `${c.name} must NOT appear in the attacker's org`).to.be.null
        })
        findBy(c.listUrl, c.match(victimTag)).then((row) => {
          expect(row, `${c.name} must not leak into the attacker's list under its original name`).to.be.null
        })
      })

      it('the victim still owns the row, with its values intact', () => {
        // ── The whole point: the row is still in tenant A's org, unmodified.
        cy.loginAsEduOwner()
        findBy(c.listUrl, c.match(victimTag)).then((row) => {
          expect(row, `${c.name} still belongs to tenant A`).to.not.be.null
          expect(JSON.stringify(row), `${c.name} field values were not overwritten`)
            .to.not.contain(stolenTag)
        })
      })
    })
  })

  /**
   * Fee collection is the money path, so it gets its own case rather than riding the table above:
   * a takeover here moves a PAYMENT RECORD out of the owning school's books.
   */
  describe('FeeCollection (money)', () => {
    const victimEnroll = `CYFEE${uniq()}`
    let feeId = null

    before(() => {
      cy.loginAsEduOwner()
      // Slice 0.2a: a tendered payment settles against a STUDENT, so one must exist — a fee for an unknown
      // enrolment number is now refused rather than silently failing to settle.
      post('/addStudent', { name: `CY_FEEOWNER_${uniq()}`, enrollNo: victimEnroll, status: 'ACTIVE' })
        .then((r) => expect(JSON.stringify(r.body), 'student for the fee owner created').to.match(/SUCCESS/))
      // Slice 0.4 renamed the fee fields (f→fee, dt→discountType, d→discount).
      post('/addFc', { enrollNo: victimEnroll, fee: 5000, dueAmount: 5000, feePaid: 5000,
                       discountType: 'Percent', discount: 0 }).then((r) => {
        expect(JSON.stringify(r.body), 'fee record created by tenant A').to.match(/SUCCESS/)
      })
      // Print what the list actually returned — a null here can mean an empty list, an unauthenticated
      // redirect (HTML, not JSON), or an enrollNo that did not bind. The bare "expected null" said none of that.
      cy.request({ url: '/getUserFc', failOnStatusCode: false }).then((r) => {
        const got = rows(r.body).map((x) => x.enrollNo)
        cy.log(`getUserFc returned ${got.length} row(s); looking for ${victimEnroll}`)
        expect(JSON.stringify(r.body).slice(0, 300), 'getUserFc returned JSON, not an HTML redirect')
          .to.not.match(/<!DOCTYPE|<html/i)
        expect(got, `enrolments returned: ${JSON.stringify(got.slice(0, 20))}`).to.include(victimEnroll)
      })
      findBy('/getUserFc', (x) => x.enrollNo === victimEnroll).then((row) => {
        expect(row, 'fee record readable by its owner').to.not.be.null
        feeId = row.id != null ? row.id : row.fcId
        expect(feeId, 'fee record id captured').to.exist
      })
    })

    it('tenant B cannot overwrite another school\'s payment record', () => {
      cy.loginAsEducation()
      cy.then(() => post('/addFc', { id: feeId, enrollNo: victimEnroll, fee: 1, feePaid: 1 })).then((r) => {
        expect(JSON.stringify(r.body), 'fee takeover must be refused').to.not.match(/SUCCESS/)
      })
    })

    it('the original payment is unchanged in the owning school\'s books', () => {
      cy.loginAsEduOwner()
      findBy('/getUserFc', (x) => x.enrollNo === victimEnroll).then((row) => {
        expect(row, 'fee record still belongs to tenant A').to.not.be.null
        expect(Number(row.f), 'the fee amount was not rewritten').to.eq(5000)
      })
    })
  })

  /**
   * Child references: a save also ATTACHES rows by id (school→owners, staff→classes, subject→class).
   * Those lookups were unscoped too, so a caller could pull another tenant's owner/class onto their own
   * record — leaking that row's data back through every read of the parent.
   */
  describe('Child references cannot be pulled across tenants', () => {
    const ownerTag = `CY_XOWNER_${uniq()}`
    let foreignOwnerId = null

    before(() => {
      cy.loginAsEduOwner()
      post('/addOwner', { name: ownerTag, mobile: '03009999999', address: 'X', status: 'Active' })
      findBy('/getUserOwner', (x) => x.name === ownerTag).then((row) => {
        expect(row, 'foreign owner exists in tenant A').to.not.be.null
        foreignOwnerId = row.id != null ? row.id : row.ownerId
      })
    })

    it('a school cannot attach an owner belonging to another tenant', () => {
      const branch = `CY_BRANCH_${uniq()}`
      cy.loginAsEducation()

      cy.then(() => post('/addSchool', {
        name: branch, branchName: branch, status: 'Active', ownerIds: foreignOwnerId,
      })).then((r) => {
        // The school itself may save — what must NOT happen is the foreign owner being attached.
        expect(r.status, 'request completed').to.eq(200)
      })

      findBy('/getUserSchool', (x) => x.name === branch || x.branchName === branch).then((row) => {
        if (row === null) return   // refused outright is also acceptable
        expect(JSON.stringify(row), 'another tenant\'s owner must not be attached')
          .to.not.contain(ownerTag)
      })
    })
  })
})
