const { defineConfig } = require('cypress')

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
    video: false,
    screenshotOnRunFailure: true,
    experimentalInteractiveRunEvents: true,
  },
})
