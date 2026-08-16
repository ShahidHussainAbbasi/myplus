/**
 * Product form — the Manufacturer picker.
 *
 * Manufacturer became a dropdown of the values ALREADY IN USE on this org's products, plus an inline
 * "+ New manufacturer" box — the same shape as Category, but with no master table behind it. The field
 * is still a plain String on the product, so ProductDTO, ProductRef and the sale report are untouched.
 *
 * WHAT THIS GUARDS. A <select> can only submit a value it contains, which gives two ways to corrupt data
 * silently, and both are the reason this file exists:
 *   1. editing a product whose manufacturer is not among the options — a bare .val() would fail quietly,
 *      the select would fall back to its first option, and SAVING WOULD CHANGE THE MANUFACTURER without
 *      the user touching the field;
 *   2. a fresh product inheriting whatever sorts first, because form.reset() restores a select to its
 *      first option rather than to blank.
 *
 * Run headed.
 */

/**
 * The control the user actually sees for #prodManufacturer.
 *
 * searchable-selects.js converts eligible <select>s into a bootstrap-select: a rendered button plus a menu,
 * with the original element hidden. So `cy.get('#prodManufacturer')` inspects something invisible. When the
 * picker is present this yields its button; when it is not (plain <select>), it falls back to the select
 * itself, so the assertion is meaningful either way rather than silently passing on a missing node.
 */
function visibleManufacturer() {
  return cy.get('#prodManufacturer').then(($sel) => {
    const $picker = $sel.next('.bootstrap-select').find('button').first()
    return $picker.length ? cy.wrap($picker) : cy.wrap($sel)
  })
}

function openProductScreen() {
  cy.visit('/businessDashboard')
  cy.window().should((w) => {
    expect(w.loadManufacturers, 'catalog-products.js exposes loadManufacturers').to.be.a('function')
    expect(w.addManufacturerInline, '...and addManufacturerInline').to.be.a('function')
  })
  cy.window().then((w) => w.showProducts())
}

describe('Product list — manufacturer column', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  /**
   * The Product row is a positional array that must stay exactly as long as the #tableProduct header —
   * business.js says so in a comment, because a missing cell shifts every later column and DataTables
   * throws "Requested unknown parameter". Adding the manufacturer column is exactly that risk, so the
   * count is asserted rather than assumed.
   */
  it('the header and the rendered row have the same number of columns', () => {
    cy.seedProduct({ name: 'MfrCol_' + Date.now(), manufacturer: 'ColBrand_' + Date.now() }).then(() => {
      openProductScreen()
      // Wait for a LOADED row before comparing. `tbody tr` length > 0 is satisfied by DataTables'
      // "No data available" PLACEHOLDER, which is itself a `<tr>` carrying a single cell — so this case
      // measured the placeholder and reported "Found 1, expected 14", a number that looks like a
      // catastrophic column shift and is really just an unloaded grid. A real row has more than one cell.
      cy.waitForAppReady()
      cy.get('#tableProduct tbody tr:first td', { timeout: 20000 }).should('have.length.greaterThan', 1)

      // Re-read BOTH sides on every retry — the header count is not stable at first paint.
      //
      // `loadDataTable` calls `datatable.columns([0]).visible(false)` (column 0 is the internal row id),
      // and DataTables then REMOVES that `<th>`. So the header is 14 before it settles and 13 after,
      // while a data row is 13 throughout. The old form snapshotted the header with
      // `cy.get('thead th').then(...)` and retried only the `td` side against that frozen number — so it
      // failed as "header 14, row 13" when it read early, and a "-1" correction merely swapped the
      // failure to "expected 12, found 13" when it read late. Neither was a grid defect: the cells were
      // verified aligned (Edit→checkbox, name→NAME, … ACTIVE→STATUS).
      //
      // `should()` re-runs this whole callback, so both counts are re-read until DataTables settles.
      // The guard is unchanged: a renderer emitting the wrong number of cells still fails.
      cy.get('#tableProduct').should(($table) => {
        const th = $table.find('thead th').length
        const td = $table.find('tbody tr').first().find('td').length
        expect(td, `row cells (${td}) vs header cells (${th})`).to.eq(th)
      })
    })
  })

  it('shows the manufacturer on the product row', () => {
    const brand = 'ShowBrand_' + Date.now()
    const name = 'MfrShow_' + Date.now()
    cy.seedProduct({ name: name, manufacturer: brand }).then(() => {
      openProductScreen()
      // The table is searchable — narrow to the seeded product rather than paging to find it.
      cy.get('#tableProduct_filter input', { timeout: 20000 }).type(name)
      cy.get('#tableProduct tbody tr').should('have.length', 1)
      cy.get('#tableProduct tbody tr').first().should('contain', brand)
    })
  })

  /**
   * The toggle links carry POSITIONAL data-column indexes, so they are the first thing to break silently
   * when a column is inserted. Toggling Manufacturer off must hide the manufacturer column — not Category
   * or On hand, which is exactly what an off-by-one would do.
   */
  it('Toggle column hides and restores the Manufacturer column', () => {
    const brand = 'TogBrand_' + Date.now()
    const name = 'MfrTog_' + Date.now()
    cy.seedProduct({ name: name, manufacturer: brand }).then(() => {
      openProductScreen()
      cy.get('#tableProduct_filter input', { timeout: 20000 }).type(name)
      cy.get('#tableProduct tbody tr').should('have.length', 1).and('contain', brand)

      // Hide it — the link is scoped to the Product screen so it cannot hit another table's datatable.
      cy.get('#ProductDiv a.toggle-vis[data-column="10"]').click()
      cy.get('#tableProduct tbody tr').first().should('not.contain', brand)
      // The neighbouring columns must survive: an off-by-one would have hidden one of these instead.
      cy.get('#tableProduct thead').should('contain', 'Category').and('contain', 'On hand')

      // ...and back.
      cy.get('#ProductDiv a.toggle-vis[data-column="10"]').click()
      cy.get('#tableProduct tbody tr').first().should('contain', brand)
    })
  })

  it('a product with no manufacturer renders an empty cell, not "undefined"', () => {
    const name = 'MfrEmpty_' + Date.now()
    cy.seedProduct({ name: name }).then(() => {
      openProductScreen()
      cy.get('#tableProduct_filter input', { timeout: 20000 }).type(name)
      cy.get('#tableProduct tbody tr').should('have.length', 1)
      cy.get('#tableProduct tbody tr').first().should('not.contain', 'undefined')
      cy.get('#tableProduct tbody tr').first().should('not.contain', 'null')
    })
  })
})

describe('Product form — manufacturer picker', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  it('is a dropdown with an inline add box, mirroring Category', () => {
    openProductScreen()
    cy.get('#newProduct').click({ timeout: 30000 })
    cy.get('#prodManufacturer').should('exist').and('match', 'select')
    cy.get('#prodManufacturerNew').should('exist')
  })

  it('lists a manufacturer already in use by another product', () => {
    const brand = 'Brand_' + Date.now()
    cy.seedProduct({ name: 'MfrA_' + Date.now(), manufacturer: brand }).then(() => {
      openProductScreen()
      cy.get('#newProduct').click({ timeout: 30000 })
      // The options ARE the distinct manufacturers across the product index, so the seeded brand is offered.
      cy.get('#prodManufacturer option', { timeout: 15000 }).should('contain', brand)
    })
  })

  it('a new product starts with NO manufacturer selected', () => {
    cy.seedProduct({ name: 'MfrBlank_' + Date.now(), manufacturer: 'Aaa_' + Date.now() }).then(() => {
      openProductScreen()
      cy.get('#newProduct').click({ timeout: 30000 })
      cy.get('#prodManufacturer option', { timeout: 15000 }).should('have.length.greaterThan', 1)
      // Not the first option — a blank. form.reset() would otherwise hand the product a brand nobody chose.
      cy.get('#prodManufacturer').should('have.value', '')
    })
  })

  it('the inline box adds a value and selects it, and it round-trips through save', () => {
    const brand = 'NewBrand_' + Date.now()
    const name = 'MfrSave_' + Date.now()
    openProductScreen()
    cy.get('#newProduct').click({ timeout: 30000 })
    cy.get('#prodName').type(name)
    cy.get('#prodManufacturerNew').type(brand)
    // Addressed by the BEHAVIOUR it triggers, not by its label. The old selector was
    // `cy.contains('#ProductModal .btn-success', '+')`, which cannot match: the button renders a
    // `<span class="glyphicon glyphicon-plus">` and carries no '+' text at all — the only literal '+'
    // on screen is the INPUT's placeholder ("+ New manufacturer"). Text was being used to pick one of
    // TEN .btn-success buttons in this modal; `addManufacturerInline` is unique, and survives the icon
    // being restyled.
    cy.get('#ProductModal button[onclick^="addManufacturerInline"]').click({ force: true })

    cy.get('#prodManufacturer').should('have.value', brand)
    cy.get('#prodManufacturerNew').should('have.value', '')      // consumed

    // ...AND it is actually on screen. searchable-selects.js replaces the <select> with a bootstrap-select
    // button and hides the real element, so every assertion above passes on a hidden node — this gate went
    // green once while the button still read "— none —". Assert what the cashier sees, not just the value.
    visibleManufacturer().should('contain', brand)

    cy.get('#addProduct').click({ timeout: 30000 })
    // Saved as a plain String on the product, exactly as before this change.
    cy.request('/getUserProduct?q=-1&includeInactive=true').then((r) => {
      const mine = (r.body.collection || []).find((p) => p.name === name)
      expect(mine, 'the product saved').to.exist
      expect(mine.manufacturer, 'the typed brand reached the product').to.eq(brand)
    })
  })

  it('typing a manufacturer that already exists in another casing selects the EXISTING spelling', () => {
    const brand = 'CaseBrand' + Date.now()
    cy.seedProduct({ name: 'MfrCase_' + Date.now(), manufacturer: brand }).then(() => {
      openProductScreen()
      cy.get('#newProduct').click({ timeout: 30000 })
      cy.get('#prodManufacturer option', { timeout: 15000 }).should('contain', brand)

      cy.get('#prodManufacturerNew').type(brand.toUpperCase())
      cy.get('#ProductModal button[onclick^="addManufacturerInline"]').click({ force: true })
      // The point of offering the list: it must not create a near-duplicate that reports separately.
      cy.get('#prodManufacturer').should('have.value', brand)
      cy.get('#prodManufacturer option').then(($opts) => {
        const upper = [...$opts].filter((o) => o.value === brand.toUpperCase())
        expect(upper, 'no near-duplicate option was added').to.have.length(0)
      })
    })
  })

  /**
   * THE REGRESSION THIS FILE EXISTS FOR. Editing must never rewrite a field the user did not touch.
   */
  it('editing preserves a manufacturer, and re-saving does not change it', () => {
    const brand = 'KeepBrand_' + Date.now()
    const name = 'MfrEdit_' + Date.now()
    cy.seedProduct({ name: name, manufacturer: brand, sellingPrice: 12 }).then(({ productId }) => {
      openProductScreen()
      cy.window().then((w) => w.editProduct(productId))
      cy.get('#ProductModal', { timeout: 15000 }).should('have.class', 'open')

      // The product's own value is selected — injected as an option if the list did not already carry it.
      cy.get('#prodManufacturer', { timeout: 15000 }).should('have.value', brand)
      visibleManufacturer().should('contain', brand)   // and the operator can see it, not just the DOM

      // Save without touching the field: the manufacturer must come back unchanged.
      cy.get('#addProduct').click({ timeout: 30000 })
      cy.request('/getCatalogProduct?id=' + productId).then((r) => {
        expect(r.body.data.manufacturer, 'untouched field survives an edit').to.eq(brand)
      })
    })
  })

  it('a product with no manufacturer stays blank through an edit', () => {
    const name = 'MfrNone_' + Date.now()
    cy.seedProduct({ name: name, sellingPrice: 9 }).then(({ productId }) => {
      openProductScreen()
      cy.window().then((w) => w.editProduct(productId))
      cy.get('#ProductModal', { timeout: 15000 }).should('have.class', 'open')
      cy.get('#prodManufacturer').should('have.value', '')

      cy.get('#addProduct').click({ timeout: 30000 })
      cy.request('/getCatalogProduct?id=' + productId).then((r) => {
        const m = r.body.data.manufacturer
        expect(m == null || m === '', 'no manufacturer was invented').to.be.true
      })
    })
  })

  it('options are sorted alphabetically, case-insensitively', () => {
    const stamp = Date.now()
    cy.seedProduct({ name: 'MfrZ_' + stamp, manufacturer: 'zeta' + stamp }).then(() => {
      cy.seedProduct({ name: 'MfrA_' + stamp, manufacturer: 'Alpha' + stamp }).then(() => {
        openProductScreen()
        cy.get('#newProduct').click({ timeout: 30000 })
        cy.get('#prodManufacturer option', { timeout: 15000 }).then(($opts) => {
          const vals = [...$opts].map((o) => o.value).filter((v) => v.indexOf(String(stamp)) >= 0)
          const sorted = [...vals].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }))
          expect(vals, 'seeded brands appear in alphabetical order').to.deep.eq(sorted)
        })
      })
    })
  })
})
