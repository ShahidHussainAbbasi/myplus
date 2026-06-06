/**
 * Education — operations endpoints (Attendance, Fee Collection).
 * These sit behind different nav selects (attendanceType / feeType), so this spec
 * focuses on the flat data endpoints (GenericResponse) rather than section nav.
 */

describe('Education — operations', () => {
  beforeEach(() => {
    cy.loginAsEducation()
  })

  const ENDPOINTS = ['/getUserA', '/getAllA', '/getUserFc', '/getAllFc']

  ENDPOINTS.forEach((url) => {
    it(`${url} returns SUCCESS or NOT_FOUND`, () => {
      cy.request(url).then((res) => {
        expect(res.status).to.eq(200)
        expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND'])
      })
    })
  })
})
