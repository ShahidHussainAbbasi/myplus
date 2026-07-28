/*
 * Multilingual gate — English, French, Spanish, Hindi, Arabic, Urdu.
 *
 * Guards, in order of how badly each one bit during the build:
 *  1. The externalization gap: pass 1 of the transform matched element SHAPES, so text sitting
 *     BESIDE an element (<div><span class=icon/>Sales by Customer This Month</div>) was missed and
 *     the dashboard still rendered English. That exact string is asserted below.
 *  2. Locale resolution: Spring 6.2 replaced CookieLocaleResolver's protected determineDefaultLocale()
 *     hook with a defaultLocaleFunction field, so an override compiled fine and was NEVER CALLED —
 *     the app fell back to the browser's raw locale, ignoring the whitelist and the org default.
 *  3. Webfont selection: keyed on the LANGUAGE, not on direction. Hindi is left-to-right and still
 *     needs Devanagari, which a direction-keyed rule silently skips.
 *
 * Run (app up on :8080, demo.* accounts seeded):
 *   npx cypress run  --spec cypress/e2e/ui/i18n.cy.js
 *   npx cypress open --e2e            (headed — pick i18n.cy.js)
 */

const LANGS = [
  { tag: 'en', endonym: 'English',  dir: 'ltr', webfont: false },
  { tag: 'fr', endonym: 'Français', dir: 'ltr', webfont: false },
  { tag: 'es', endonym: 'Español',  dir: 'ltr', webfont: false },
  { tag: 'hi', endonym: 'हिन्दी',     dir: 'ltr', webfont: true  },
  { tag: 'ar', endonym: 'العربية',  dir: 'rtl', webfont: true  },
  { tag: 'ur', endonym: 'اردو',     dir: 'rtl', webfont: true  },
];

describe('Multilingual', () => {

  describe('Language switcher (public login page)', () => {
    it('offers every shipped language, each named in its own script', () => {
      cy.visit('/login');
      cy.get('.lang-switch').should('exist');

      LANGS.forEach((l) => {
        // The endonym is the point: a menu reading "Arabic" only helps someone who reads English.
        cy.get(`.lang-switch__menu a[href*="lang=${l.tag}"]`)
          .should('exist')
          .and('contain.text', l.endonym);
      });
    });

    it('marks the active language', () => {
      cy.visit('/login?lang=fr');
      cy.get('.lang-switch__menu a.is-active').should('have.attr', 'href').and('include', 'lang=fr');
    });
  });

  describe('Direction, lang attribute and webfont', () => {
    LANGS.forEach((l) => {
      it(`${l.tag}: dir=${l.dir}, lang=${l.tag}, webfont=${l.webfont}`, () => {
        cy.visit(`/login?lang=${l.tag}`);

        cy.get('html').should('have.attr', 'lang', l.tag);
        cy.get('html').should('have.attr', 'dir', l.dir);

        // Exactly one script webfont, and only for the scripts Inter cannot render.
        cy.document().then((doc) => {
          const fonts = [...doc.querySelectorAll('link[rel="stylesheet"]')]
            .map((n) => n.href)
            .filter((h) => /Noto\+(Naskh\+Arabic|Nastaliq\+Urdu|Sans\+Devanagari)/.test(h));

          if (l.webfont) {
            expect(fonts.length, `${l.tag} must load its script font`).to.be.greaterThan(0);
            if (l.tag === 'hi') {
              expect(fonts.join(' '), 'Hindi needs Devanagari despite being LTR')
                .to.include('Devanagari');
            }
          } else {
            expect(fonts.length, `${l.tag} is Latin script — no extra font download`).to.eq(0);
          }
        });

        // rtl.css must load for RTL locales only.
        cy.document().then((doc) => {
          const rtl = [...doc.querySelectorAll('link[rel="stylesheet"]')]
            .filter((n) => /\/css\/rtl\.css/.test(n.href));
          expect(rtl.length, `rtl.css present only for RTL (${l.tag})`).to.eq(l.dir === 'rtl' ? 1 : 0);
        });
      });
    });
  });

  describe('Translated content', () => {
    it('login page copy actually changes language', () => {
      cy.visit('/login?lang=es');
      cy.get('body').should('contain.text', 'Contraseña');

      cy.visit('/login?lang=ur');
      cy.get('body').should('contain.text', 'پاس ورڈ');

      cy.visit('/login?lang=hi');
      cy.get('body').should('contain.text', 'पासवर्ड');
    });

    it('the choice survives navigation (persisted in the locale cookie)', () => {
      cy.visit('/login?lang=fr');
      cy.visit('/login');                       // no ?lang= this time
      cy.get('html').should('have.attr', 'lang', 'fr');
    });

    it('an unsupported ?lang= is ignored rather than breaking the page', () => {
      cy.visit('/login?lang=fr');
      cy.visit('/login?lang=zz');                // well-formed but not shipped

      // Must keep French, NOT reset to English and NOT render raw message keys.
      cy.get('html').should('have.attr', 'lang', 'fr');
      cy.get('body').should('not.contain.text', 'label.form.');
      cy.get('body').should('not.contain.text', 'ui.js.');
    });
  });

  describe('Dashboard — the strings that were still English', () => {
    beforeEach(() => cy.loginAsBusiness());

    // This is the exact regression the user caught: text beside an icon inside a div.
    it('chart headings translate (the case the first transform missed)', () => {
      cy.visit('/businessDashboard?lang=en');
      cy.get('body').should('contain.text', 'Sales by Customer This Month');

      cy.visit('/businessDashboard?lang=ur');
      cy.get('body').should('contain.text', 'اس ماہ گاہک کے لحاظ سے فروخت');
      cy.get('body').should('not.contain.text', 'Sales by Customer This Month');
    });

    it('no raw message keys leak into any page', () => {
      ['en', 'fr', 'es', 'hi', 'ar', 'ur'].forEach((tag) => {
        cy.visit(`/businessDashboard?lang=${tag}`);
        // useCodeAsDefaultMessage(true) renders a MISSING key as the key itself — catch that.
        cy.get('body').invoke('text').should((t) => {
          expect(t, `raw ui.* key visible in ${tag}`).not.to.match(/\bui\.[a-z][A-Za-z0-9]{3,}\b/);
          expect(t, `raw label.* key visible in ${tag}`).not.to.match(/\blabel\.[a-z]+\.[a-z]+\b/);
        });
      });
    });

    it('script strings come from the same bundle as the markup', () => {
      cy.visit('/businessDashboard?lang=ur');
      cy.window().then((w) => {
        expect(w.__MSG, 'JS dictionary is rendered into the page').to.be.an('object');
        expect(Object.keys(w.__MSG).length, 'ui.js.* keys shipped').to.be.greaterThan(50);

        // Only the ui.js.* subset crosses to the browser — server-only copy must not.
        Object.keys(w.__MSG).forEach((k) => expect(k).to.match(/^ui\.js\./));

        expect(w.t('ui.js.voidFailed'), 't() resolves a translated string')
          .to.eq('کالعدم کرنا ناکام');
      });
    });
  });

  describe('Region auto-detection', () => {
    // Before this, CookieLocaleResolver had a hardcoded ENGLISH default: a first-time visitor in
    // Lahore or Cairo got English no matter what their browser asked for.
    it('a fresh visitor gets the language their browser asks for', () => {
      cy.clearCookies();
      cy.request({ url: '/login', headers: { 'Accept-Language': 'ur-PK,ur;q=0.9,en;q=0.8' } })
        .its('body').should('include', 'lang="ur"');

      cy.clearCookies();
      cy.request({ url: '/login', headers: { 'Accept-Language': 'hi-IN,hi;q=0.9' } })
        .its('body').should('include', 'lang="hi"');
    });

    it('skips languages we do not ship and takes the next preference', () => {
      cy.clearCookies();
      // German is not shipped; French is — the visitor must get French, not English.
      cy.request({ url: '/login', headers: { 'Accept-Language': 'de-DE,de;q=0.9,fr;q=0.7' } })
        .its('body').should('include', 'lang="fr"');
    });

    it('falls back to English when the browser asks for nothing we ship', () => {
      cy.clearCookies();
      cy.request({ url: '/login', headers: { 'Accept-Language': 'de-DE,de;q=0.9' } })
        .its('body').should('include', 'lang="en"');
    });

    it('an explicit choice outranks the browser', () => {
      cy.clearCookies();
      cy.visit('/login?lang=ar');                                  // sets the cookie
      cy.request({ url: '/login', headers: { 'Accept-Language': 'fr-FR,fr;q=0.9' } })
        .its('body').should('include', 'lang="ar"');
    });
  });

  describe('RTL layout', () => {
    beforeEach(() => cy.loginAsBusiness());

    it('Arabic mirrors the shell: sidebar on the right, no sideways scroll', () => {
      cy.viewport(1280, 800);
      cy.visit('/businessDashboard?lang=ar');
      cy.get('.app-sidebar', { timeout: 10000 }).should('exist');

      cy.get('.app-sidebar').then(($sb) => {
        const rect = $sb[0].getBoundingClientRect();
        expect(Math.round(rect.right), 'rail sits against the right edge')
          .to.be.closeTo(Cypress.config('viewportWidth'), 4);
      });

      cy.get('body').then(($b) => {
        const padR = parseFloat(getComputedStyle($b[0]).paddingRight) || 0;
        expect(padR, 'content reserves the rail width on the right').to.be.greaterThan(200);
      });

      cy.document().then((doc) => {
        const el = doc.documentElement;
        expect(el.scrollWidth, 'no horizontal overflow in RTL').to.be.at.most(el.clientWidth + 2);
      });
    });

    it('Latin content inside an RTL page stays left-to-right', () => {
      cy.visit('/businessDashboard?lang=ur');
      // Email/number inputs must not have their characters reordered around punctuation.
      cy.get('input[type="email"], input[type="number"]').first().should(($el) => {
        expect(getComputedStyle($el[0]).direction).to.eq('ltr');
      });
    });
  });
});
