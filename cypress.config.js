const { defineConfig } = require('cypress')
const crypto = require('crypto')

// ── RFC 6238 TOTP (SHA1, 30s, 6 digits) — to exercise the 2FA verify step delegated to auth-service.
function base32Decode(b32) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  let bits = ''
  const out = []
  b32 = (b32 || '').replace(/=+$/, '').toUpperCase().replace(/\s/g, '')
  for (const c of b32) {
    const v = alphabet.indexOf(c)
    if (v < 0) continue
    bits += v.toString(2).padStart(5, '0')
  }
  for (let i = 0; i + 8 <= bits.length; i += 8) out.push(parseInt(bits.substr(i, 8), 2))
  return Buffer.from(out)
}
function totp(secret, step = 30, digits = 6) {
  const key = base32Decode(secret)
  let counter = Math.floor(Date.now() / 1000 / step)
  const buf = Buffer.alloc(8)
  for (let i = 7; i >= 0; i--) { buf[i] = counter & 0xff; counter = Math.floor(counter / 256) }
  const hmac = crypto.createHmac('sha1', key).update(buf).digest()
  const offset = hmac[hmac.length - 1] & 0xf
  const code = ((hmac[offset] & 0x7f) << 24) | ((hmac[offset + 1] & 0xff) << 16)
    | ((hmac[offset + 2] & 0xff) << 8) | (hmac[offset + 3] & 0xff)
  return (code % (10 ** digits)).toString().padStart(digits, '0')
}

module.exports = defineConfig({
  e2e: {
    baseUrl: 'http://localhost:8080',
    viewportWidth: 1280,
    viewportHeight: 800,
    defaultCommandTimeout: 5000,
    pageLoadTimeout: 60000,
    specPattern: 'cypress/e2e/**/*.cy.js',
    screenshotsFolder: 'cypress/screenshots',
    videosFolder: 'cypress/videos',
    // Off by default (Cypress default); enable per-run with `--config video=true`
    // (e.g. npm run test:e2e:education:demo).
    screenshotOnRunFailure: true,
    experimentalInteractiveRunEvents: true,
    setupNodeEvents(on) {
      on('task', {
        // Compute the current authenticator code from an otpauth:// secret (2FA verify test).
        totp({ secret }) {
          return totp(secret)
        },
      })
    },
  },
})
