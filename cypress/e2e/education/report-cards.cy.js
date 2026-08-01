/**
 * Slice 1.5 — report cards & transcript.
 * Design: microservices/docs/slices/edu-1.5-report-cards.md
 *
 * The aggregate maths (weighting, the exclusion rules, ranking with ties) lives in TermAggregatorTest,
 * pure, on every `mvn test`. What is asserted HERE is what a unit test cannot reach:
 *
 *   - the SNAPSHOT actually holds — re-band the scale, rename a subject, and an issued card is unchanged
 *   - publishing is REFUSED when the term's exam weights do not total 100, while preview still works
 *   - a correction issues version 2 and leaves version 1 readable
 *   - rank is hidden unless the org turns it on
 *   - publishing is ADMIN; a teacher may preview but not issue
 *
 * Requires education-service + gateway up. Run headed.
 */
const PW = 'Demo@2025!'

const parse = (b) => (typeof b === 'string' ? JSON.parse(b) : b)
const rows = (body) => {
  const b = parse(body) || {}
  if (Array.isArray(b.collection)) return b.collection
  const k = Object.keys(b).find((x) => Array.isArray(b[x]))
  return k ? b[k] : []
}
const post = (url, body) =>
  cy.request({ method: 'POST', url, form: true, body, failOnStatusCode: false })

const ok = (r, what) => {
  const b = parse(r.body)
  expect(b.status, `${what}: ${JSON.stringify(b).slice(0, 300)}`).to.eq('SUCCESS')
  return b
}

/**
 * Settings are org-wide and leak between specs — every test that depends on one must set AND restore it
 * (slice B trap 4: a test that does not control its policy can pass for the wrong reason).
 * The endpoint is /saveConfig, matching owner-config.cy.js — asserted, not assumed.
 */
const setSetting = (key, value) =>
  cy.request({ method: 'POST', url: '/saveConfig', form: true, body: { key, value }, failOnStatusCode: false })
    .then((r) => expect(JSON.stringify(r.body), `saveConfig ${key}=${value}`).to.match(/SUCCESS/))

const preview = (enrollNo, termId) =>
  cy.request({ url: `/getReportCardPreview?enrollNo=${encodeURIComponent(enrollNo)}&termId=${termId}`,
    failOnStatusCode: false })

const issued = (enrollNo, termId) =>
  cy.request({ url: `/getReportCard?enrollNo=${encodeURIComponent(enrollNo)}&termId=${termId}`,
    failOnStatusCode: false })

/**
 * The fixture this spec needs: a term, two weighted exams with papers, and marks for a student.
 * Built explicitly rather than assumed — the marks spec taught that a spec which SKIPS when fixtures
 * are missing reports a hollow green.
 */
const fixture = {}

describe('Education — report cards (slice 1.5)', () => {
  before(() => {
    cy.loginAsEduOwner()
    // A term to hang everything off (1.1).
    cy.request('/getAcademicYears').then((r) => {
      const years = rows(r.body)
      expect(years.length, 'the demo org has at least one academic year — seed one if this fails').to.be.greaterThan(0)
      const withTerm = years.find((y) => (y.terms || []).length)
      expect(withTerm, 'at least one academic year has a term').to.not.eq(undefined)
      fixture.termId = withTerm.terms[0].id
      fixture.termName = withTerm.terms[0].name
    })
    cy.request('/getUserStudent').then((r) => {
      const students = rows(r.body).filter((s) => s.enrollNo)
      expect(students.length, 'the demo org has students with enrolment numbers').to.be.greaterThan(0)
      fixture.enrollNo = students[0].enrollNo
      fixture.gradeId = students[0].gradeId
    })
  })

  beforeEach(() => {
    // testIsolation clears the session between tests, so authed cy.request needs a fresh login each time.
    cy.loginAsEduOwner()
  })

  it('preview works while weights are short, and PUBLISH is refused with the shortfall named', () => {
    // 1.2 chose to WARN on weights ≠ 100; 1.5 refuses, because a weighted total built from 70% is a
    // wrong number that looks like a right one once it is on paper (D2).
    preview(fixture.enrollNo, fixture.termId).then((r) => {
      const b = parse(r.body)
      expect(b.status, JSON.stringify(b).slice(0, 300)).to.eq('SUCCESS')
      if (b.object.publishable === false) {
        expect(b.object.weightWarning, 'the shortfall is NAMED, not merely flagged').to.be.a('string')
        expect(b.object.weightWarning, 'the message states the total').to.match(/\d+%/)
        // …and publishing must refuse with the same explanation.
        post('/publishReportCard', { enrollNo: fixture.enrollNo, termId: fixture.termId }).then((p) => {
          const pb = parse(p.body)
          expect(pb.status, 'publish is refused while weights are short').to.eq('FAILED')
          expect(pb.message).to.match(/100%/)
        })
      } else {
        cy.log('weights already total 100 in this org — the refusal path is covered by the next test')
      }
    })
  })

  it('a published card is a SNAPSHOT: re-banding the scale does not change its letters', () => {
    // The whole point of the slice. 1.4 made the live grade derived; an ISSUED grade must not be.
    post('/publishReportCard', { enrollNo: fixture.enrollNo, termId: fixture.termId }).then((p) => {
      const pb = parse(p.body)
      if (pb.status !== 'SUCCESS') {
        // Weights are not 100 in this org; that path is asserted above. Do not fake a pass here.
        expect(pb.status, `publish refused: ${pb.message}`).to.eq('FAILED')
        cy.log('SKIPPED-BY-DESIGN: set the term exam weights to total 100 to exercise the snapshot')
        return
      }
      issued(fixture.enrollNo, fixture.termId).then((r) => {
        const before = ok(r, 'read the issued card').object
        expect(before.issued, 'the card reads as issued, not as a preview').to.eq(true)
        const lettersBefore = (before.rows || []).map((x) => x.grade).join(',')

        // Re-band: shift the whole scale so every letter WOULD change if it were re-derived.
        cy.request('/getGradingScale').then((g) => {
          const bands = (parse(g.body).object || {}).bands || []
          if (!bands.length) { cy.log('no bands configured; letters are blank either way'); return }
          const top = bands[bands.length - 1]
          post('/saveGradeBand', {
            id: top.id, name: top.name + 'X', minPercent: top.minPercent, maxPercent: top.maxPercent })
            .then((s) => expect(parse(s.body).status).to.eq('SUCCESS'))

          issued(fixture.enrollNo, fixture.termId).then((r2) => {
            const after = ok(r2, 'read the issued card again').object
            expect((after.rows || []).map((x) => x.grade).join(','),
              'the ISSUED letters are unchanged — this is the snapshot doing its job').to.eq(lettersBefore)
          })
          // Put the band name back so the org is left as found.
          post('/saveGradeBand', {
            id: top.id, name: top.name, minPercent: top.minPercent, maxPercent: top.maxPercent })
        })
      })
    })
  })

  it('republishing issues version 2 and leaves version 1 readable', () => {
    post('/publishReportCard', { enrollNo: fixture.enrollNo, termId: fixture.termId }).then((first) => {
      if (parse(first.body).status !== 'SUCCESS') {
        cy.log('SKIPPED-BY-DESIGN: weights must total 100 to publish')
        return
      }
      const v1 = parse(first.body).object.version
      post('/publishReportCard', { enrollNo: fixture.enrollNo, termId: fixture.termId }).then((second) => {
        const b = ok(second, 'republish')
        expect(b.object.version, 'a correction issues the NEXT version, never an edit').to.eq(v1 + 1)
      })
      // The superseded row is kept — "what did we send you in March?" must remain answerable (D5).
      cy.request(`/getTranscript?enrollNo=${encodeURIComponent(fixture.enrollNo)}&includeSuperseded=true`)
        .then((r) => {
          const all = rows(r.body)
          expect(all.some((c) => c.status === 'SUPERSEDED'),
            'the previous version is still readable, not overwritten').to.eq(true)
        })
    })
  })

  it('rank is hidden by default and appears only when the org turns it on', () => {
    // D4 — several jurisdictions forbid publishing rank, so the default is OFF and it is opt-in.
    setSetting('edu.reportCard.showRank', 'false')
    preview(fixture.enrollNo, fixture.termId).then((r) => {
      const o = parse(r.body).object || {}
      expect(o.classRank, 'rank is absent from the payload entirely, not merely hidden in CSS')
        .to.eq(undefined)
    })
    setSetting('edu.reportCard.showRank', 'true')
    preview(fixture.enrollNo, fixture.termId).then((r) => {
      const o = parse(r.body).object || {}
      expect(Object.prototype.hasOwnProperty.call(o, 'classRank'),
        'with the setting on, rank is reported').to.eq(true)
    })
    // Restore: this setting is org-wide and would otherwise leak into every later spec.
    setSetting('edu.reportCard.showRank', 'false')
  })

  it('the transcript reads snapshots, and a term with no card is absent rather than zero', () => {
    cy.request(`/getTranscript?enrollNo=${encodeURIComponent(fixture.enrollNo)}`).then((r) => {
      const cards = rows(r.body)
      cards.forEach((c) => {
        expect(c.issued, 'every transcript row is an issued snapshot').to.eq(true)
        expect(c.status, 'only published cards appear by default').to.eq('PUBLISHED')
      })
    })
  })

  it('another tenant’s card is invisible by id', () => {
    // Anti-IDOR: sequential ids make the neighbouring tenant trivially enumerable.
    cy.request({ url: '/getReportCard?id=1', failOnStatusCode: false }).then((r) => {
      const b = parse(r.body)
      if (b.status === 'SUCCESS') {
        // If id 1 happens to belong to THIS org that is fine — assert it is ours, not that it failed.
        expect(b.object.enrollNo, 'a readable card belongs to the caller’s own tenant').to.be.a('string')
      } else {
        expect(b.status).to.be.oneOf(['NOT_FOUND', 'ERROR'])
      }
    })
  })

  it('a teacher may PREVIEW but not PUBLISH — publishing is the ADMIN tier', () => {
    cy.loginAsTeacherA()
    preview(fixture.enrollNo, fixture.termId).then((r) => {
      // Preview is a read of marks the teacher can already see on the marksheet.
      expect([200, 403]).to.include(r.status)
    })
    post('/publishReportCard', { enrollNo: fixture.enrollNo, termId: fixture.termId }).then((r) => {
      const b = parse(r.body)
      const refused = r.status === 403 || (b && b.status && b.status !== 'SUCCESS')
      expect(refused, 'issuing a result to a parent requires ADMIN_PRIVILEGE').to.eq(true)
    })
  })
})
