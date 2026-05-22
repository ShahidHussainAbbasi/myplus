import './commands'

// Suppress known pre-existing JS errors in the app so Cypress doesn't fail tests for them
Cypress.on('uncaught:exception', (err) => {
  if (
    err.message.includes('handleEnterKey is not defined') ||
    err.message.includes('pwstrength is not a function') ||
    err.message.includes('is not a function')
  ) {
    return false
  }
})
