/**
 * HMS — the four standing accounts of the HMS Phase 1 Test Book (client, 2026-10-12): ONE place, used by the specs
 * and by the manual tester. Created on the screens by cypress/e2e/hms/hms-four-accounts.cy.js — run it first on a
 * fresh stack.
 *
 *   OWNER       the business owner (holds everything)
 *   RECEPTION   "Clinic reception" set — registers patients, issues tokens, takes payment; does not dispense
 *   DOCTOR      "Doctor" set, LINKED to the clinic doctor DOCTOR_NAME — works only that doctor's queue (H2 rule)
 *   PHARMACIST  "Pharmacist" set (RW-1, auth V23) — sells and dispenses; does not receive stock (decision D3)
 *
 * A spec that moves one of them onto another set puts it back on OWN_SET — never on "Administrator".
 */
export const HMS = {
  OWNER: 'owner.pharma@myplus.com',
  RECEPTION: 'hms.reception@myplus.test',
  DOCTOR: 'hms.doctor@myplus.test',
  PHARMACIST: 'hms.pharmacist@myplus.test',
  PW: 'Demo@2025!',
  DOCTOR_NAME: 'Dr HMS Test',
}

export const OWN_SET = {
  [HMS.RECEPTION]: 'Clinic reception',
  [HMS.DOCTOR]: 'Doctor',
  [HMS.PHARMACIST]: 'Pharmacist',
}
