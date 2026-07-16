/**
 * Cross-tenant gate for campaign-service and analytics-service.
 *
 * Both services shipped with NO organization column, so every read/write resolved on a raw id and any
 * authenticated user could reach any tenant's campaigns, reports, metrics and dashboard widgets. This spec
 * drives that attack across two real tenants and proves the boundary now holds.
 *
 * These services are NOT wired into the monolith UI — the only way to reach them is the gateway — so the spec
 * talks to http://localhost:8765 directly with a Bearer token, rather than the monolith baseUrl. Cypress
 * allows an absolute URL in cy.request regardless of baseUrl.
 *
 * Two tenants, both seeded and no write-cap, in DIFFERENT organizations:
 *   A = owner.business@myplus.com     B = owner.education@myplus.com
 * (campaign/analytics are vertical-agnostic, so the userType difference is irrelevant — only the org differs.)
 */
const GW = 'http://localhost:8765';
const PW = 'Demo@2025!';

// ApiResponse<T> = { success, data, message }. A refusal is success:false OR a 4xx.
const dataOf = (body) => (body && body.data !== undefined ? body.data : body);
const listOf = (body) => {
  const d = dataOf(body) || {};
  // PageResponse wraps rows in `content`; fall back to a bare array.
  if (Array.isArray(d)) return d;
  if (Array.isArray(d.content)) return d.content;
  return [];
};

const login = (email) =>
  cy.request({
    method: 'POST', url: `${GW}/api/auth/login`,
    headers: { 'Content-Type': 'application/json' },
    body: { email, password: PW }, failOnStatusCode: false,
  }).then((r) => {
    expect(r.status, `login ${email}: ${JSON.stringify(r.body)}`).to.eq(200);
    const token = r.body.data.accessToken;
    expect(token, 'got an access token').to.be.a('string');
    return token;
  });

// Authed request against the gateway with a specific tenant's token.
const asTenant = (token, opts) =>
  cy.request(Object.assign({ failOnStatusCode: false }, opts, {
    headers: Object.assign({ Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      opts.headers || {}),
  }));

// A response that must be a refusal: either a 4xx, or a 2xx envelope with success:false. A 200/success:true
// that returned the victim's row is the failure this gate exists to catch.
const expectDenied = (r, msg) => {
  const ok2xx = r.status >= 200 && r.status < 300;
  const leaked = ok2xx && r.body && r.body.success === true && dataOf(r.body) && dataOf(r.body).id;
  expect(leaked, `${msg} — cross-tenant access LEAKED: ${JSON.stringify(r.body)}`).to.not.be.ok;
};

describe('Security: campaign + analytics are tenant-isolated', () => {
  let tokenA;   // owner.business — the victim tenant
  let tokenB;   // owner.education — the attacker tenant

  before(() => {
    login('owner.business@myplus.com').then((t) => { tokenA = t; });
    login('owner.education@myplus.com').then((t) => { tokenB = t; });
  });

  // ── campaign-service ────────────────────────────────────────────────────────────────────────────
  describe('campaign-service', () => {
    let campaignId;
    const name = `CY_A_Campaign_${Date.now()}`;

    it('tenant A creates a campaign', () => {
      asTenant(tokenA, {
        method: 'POST', url: `${GW}/api/campaign/campaigns`,
        body: { name, type: 'EMAIL' },
      }).then((r) => {
        expect(r.status, JSON.stringify(r.body)).to.eq(200);
        campaignId = dataOf(r.body).id;
        expect(campaignId, 'created campaign has an id').to.be.ok;
      });
    });

    it('tenant B cannot read A\'s campaign by id', () => {
      asTenant(tokenB, { method: 'GET', url: `${GW}/api/campaign/campaigns/${campaignId}` })
        .then((r) => expectDenied(r, 'GET campaign by id'));
    });

    it('tenant B\'s campaign list does not contain A\'s campaign', () => {
      asTenant(tokenB, { method: 'GET', url: `${GW}/api/campaign/campaigns?size=200` }).then((r) => {
        const ids = listOf(r.body).map((c) => c.id);
        expect(ids, 'A\'s campaign is invisible to B').to.not.include(campaignId);
      });
    });

    it('tenant B cannot update or delete A\'s campaign, and A\'s campaign survives', () => {
      asTenant(tokenB, {
        method: 'PUT', url: `${GW}/api/campaign/campaigns/${campaignId}`,
        body: { name: 'HACKED', type: 'EMAIL' },
      }).then((r) => expectDenied(r, 'PUT campaign'));

      asTenant(tokenB, { method: 'DELETE', url: `${GW}/api/campaign/campaigns/${campaignId}` })
        .then((r) => expectDenied(r, 'DELETE campaign'));

      // Verified from the OWNER's side — the real proof it neither changed nor vanished.
      asTenant(tokenA, { method: 'GET', url: `${GW}/api/campaign/campaigns/${campaignId}` }).then((r) => {
        expect(r.status, 'A can still read its campaign').to.eq(200);
        expect(dataOf(r.body).name, 'name was NOT changed by B').to.eq(name);
      });
    });

    it('tenant A sees its own campaign in its list (positive control)', () => {
      asTenant(tokenA, { method: 'GET', url: `${GW}/api/campaign/campaigns?size=200` }).then((r) => {
        expect(listOf(r.body).map((c) => c.id), 'A sees its own campaign').to.include(campaignId);
      });
    });
  });

  // ── analytics-service: reports ──────────────────────────────────────────────────────────────────
  describe('analytics-service reports', () => {
    let reportId;
    const name = `CY_A_Report_${Date.now()}`;

    it('tenant A creates a report', () => {
      asTenant(tokenA, {
        method: 'POST', url: `${GW}/api/analytics/reports`,
        body: { name, type: 'SALES', scheduleType: 'MANUAL', isActive: true },
      }).then((r) => {
        expect(r.status, JSON.stringify(r.body)).to.eq(200);
        reportId = dataOf(r.body).id;
        expect(reportId).to.be.ok;
      });
    });

    it('tenant B cannot read A\'s report by id', () => {
      asTenant(tokenB, { method: 'GET', url: `${GW}/api/analytics/reports/${reportId}` })
        .then((r) => expectDenied(r, 'GET report by id'));
    });

    it('tenant B cannot delete A\'s report, and it survives', () => {
      asTenant(tokenB, { method: 'DELETE', url: `${GW}/api/analytics/reports/${reportId}` })
        .then((r) => expectDenied(r, 'DELETE report'));
      asTenant(tokenA, { method: 'GET', url: `${GW}/api/analytics/reports/${reportId}` })
        .then((r) => expect(r.status, 'A can still read its report').to.eq(200));
    });
  });

  // ── analytics-service: dashboard widgets (per-USER isolation, not just per-org) ──────────────────
  describe('analytics-service dashboard widgets', () => {
    let widgetId;

    it('tenant A adds a widget', () => {
      asTenant(tokenA, {
        method: 'POST', url: `${GW}/api/analytics/dashboard/widgets`,
        body: { widgetType: 'COUNTER', title: 'CY A widget', position: 0, isActive: true },
      }).then((r) => {
        expect(r.status, JSON.stringify(r.body)).to.eq(200);
        widgetId = dataOf(r.body).id;
        expect(widgetId).to.be.ok;
      });
    });

    it('another user cannot edit or delete A\'s widget', () => {
      // A widget is personal, so B (a different user) must be refused even reaching it by id.
      asTenant(tokenB, {
        method: 'PUT', url: `${GW}/api/analytics/dashboard/widgets/${widgetId}`,
        body: { widgetType: 'BAR_CHART', title: 'HACKED', position: 0, isActive: true },
      }).then((r) => expectDenied(r, 'PUT widget'));

      asTenant(tokenB, { method: 'DELETE', url: `${GW}/api/analytics/dashboard/widgets/${widgetId}` })
        .then((r) => expectDenied(r, 'DELETE widget'));

      // A still owns it, unchanged.
      asTenant(tokenA, { method: 'GET', url: `${GW}/api/analytics/dashboard/widgets` }).then((r) => {
        const mine = listOf(r.body).find((w) => w.id === widgetId);
        expect(mine, 'A\'s widget survives').to.exist;
        expect(mine.title, 'title was NOT changed by B').to.eq('CY A widget');
      });
    });
  });
});
