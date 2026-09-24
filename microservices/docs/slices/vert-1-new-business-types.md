# VERT-1 — what it costs to add a business type

**Status (2026-09-24): step 1 DONE (monolith 26/0, red proven). Steps 2–4 designed, not built.**

Raised by the user: *"how to manage and implement new business types like BBQ, fast-food restaurant,
hotels and etc…?"*

Parent design: [vertical profile](../vertical-profile-any-business-design.md) ·
[restaurant vertical](../restaurant-vertical-design.md)

---

## 1 · The answer in one line

**A business type is `Shape` × `Capability`, and both are tenant DATA.** A new type should be a row, not a
release.

| Axis | What it decides | Where it lives |
|---|---|---|
| `Shape` | the information architecture — what screens are called, which dashboard opens | `org.shape` setting, per tenant |
| `Capability` | what the tenant may DO | preset by shape, overridden per tenant |

Live today: **14 tenants on `retail`, 11 on `pharmacy`, 3 on `general`** — the mechanism is not theoretical.

**The test that decides every case:** *can this be expressed as an existing shape plus capabilities?* If yes
it is not a new shape. BBQ and fast food are one `RESTAURANT` shape; a café is `RESTAURANT` without
`KITCHEN_TICKETS`. A hotel **is** a new shape, because occupancy over time has no retail equivalent.

---

## 2 · ⚠ The parent design doc has drifted — verified 2026-09-24

Two of its five red findings no longer hold. Anyone planning from §2 of that document would be planning
against a codebase that has moved.

| Doc says | Verified reality |
|---|---|
| #1 `DASHBOARD_BY_TYPE` unknown → `LANDING`; *"every user in that tenant silently bounced to the landing page on every login"* | **FIXED.** `getOrDefault(key, COMMERCE_DASHBOARD)`. An unknown commerce type now lands on a working dashboard with no Java edit |
| #3 `CommerceDashboardController.COMMERCE_MODULES` — a second copy of #2 | **GONE.** No such constant exists |
| #2 `ModuleRouter.COMMERCE_TYPES` hand-maintained | was still true — **fixed by this slice**, §3 |
| #4 `module-theme.js VERTICALS` hardcoded | still true, but **less severe than stated** — §4 |
| #5 i18n across 6 bundles | still true, and still the real blocker — §5 |

⚠ **And a correction I owe the reader.** I described steps 1 and 2 to the user as *"the two cheap red
fixes"*. Step 1 was cheap. **Step 2 is not**, and calling it so was wrong — see §4 for what it actually
costs and why the estimate changed once I read the code rather than the doc.

---

## 3 · ✅ Step 1 — `isCommerce` is derived, not enumerated

**Before:** `Set.of("BUSINESS", "PHARMA", "MARKETPLACE")`, sitting three fields from `DASHBOARD_BY_TYPE`,
which already recorded the same fact by pointing those three types at `COMMERCE_DASHBOARD`. Two statements
of one truth. Add `RESTAURANT` to the map, forget the set, and `isCommerce("RESTAURANT")` answers false
while the tenant sits on the commerce dashboard.

**After:** the set is computed from the map. A commerce vertical *is* a type whose dashboard is the commerce
dashboard, so the two cannot disagree.

⚠ **Declaration order is load-bearing.** The derived field must sit **after** `DASHBOARD_BY_TYPE`: static
initialisers run in source order, and a field above the map it reads would initialise from a null map and
leave every type looking non-commerce. Recorded in the code, because it looks like tidiness and is not.

**Evidence.** `ModuleRouterTest` 26/0 across four nested classes. The new case asserts a PROPERTY rather than
a list — for every known type, "is it commerce?" and "does it route to the commerce dashboard?" are the same
answer — so it cannot be satisfied by a hand-maintained list that happens to agree today. Proven red by
reverting to `Set.of("BUSINESS","PHARMA")`: *"MARKETPLACE: isCommerce must agree with where the router
actually sends it"*, 2 failures, then green on restore.

It also pins the case that is easy to conflate: an unknown type **routes** to commerce (the C2 fallback) but
is **not** a registered commerce vertical. A future "simplification" making `isCommerce` true for any
unknown string would hand marketplace behaviour to education typos.

---

## 4 · Step 2 — `module-theme.js`, and a corrected estimate

The doc marks this 🔴 alongside the routing bug. Reading the code, it is **not the same severity**:

```js
var profile = VERTICALS[mod] || VERTICALS.BUSINESS;
```

An unrecognised module already falls back to BUSINESS branding. So a new vertical does not break — it gets
POS wording until somebody edits the file. That is a **cosmetic default, not a silent failure**, which is
what separates it from the routing bug that genuinely stranded users.

**Why it is still worth doing:** the labels are the vertical. A restaurant reading "Sale" where it expects
"Order", and "Product" where it expects "Menu item", is a demo that lands badly — and the dictionary is
shipped JS, so every new type is a front-end release.

**Why it is not cheap:**

| | Cost |
|---|---|
| A server endpoint serving the profile by shape | new endpoint + DTO |
| The JS boot becomes ASYNC | today it runs synchronously at `DOMContentLoaded` |
| Flash of wrong label | the page paints BUSINESS wording, then relabels — visible, and worse than the static version |

That last point is the real work, and it is why this is its own slice rather than a follow-on edit. The
honest sequencing is: **serve the dictionary with the page** (Thymeleaf, same request, no flash) rather than
fetch it after paint.

**Not built. Recommended as VERT-2, sized properly.**

---

## 5 · Step 3 — i18n, the actual blocker

Each new shape needs its wording across **six locales**, and translation is not mechanical. This is the
constraint that decides how fast the platform can say yes to a new customer, and it is a business decision
rather than an engineering one:

- **Ship English-only and translate later** — fast to onboard, but a Pakistani restaurant using the Urdu UI
  meets English menu wording.
- **Wait for translation** — consistent, but a new vertical is gated on six bundle edits.
- **Fall back per key** — a missing vertical key resolves to the BUSINESS key in that language. Wording is
  generic but never English-in-an-Urdu-screen.

The third is the only one that is both fast and consistent, and it needs a ruling before RESTAURANT ships in
a non-English tenant.

---

## 6 · Step 4 — registering `RESTAURANT`

Once 1–3 are settled, a new commerce vertical is:

1. a `Shape` enum entry with its capability preset;
2. a `DASHBOARD_BY_TYPE` entry (now the only Java edit, and `isCommerce` follows it automatically);
3. a label dictionary;
4. translation, per the §5 ruling.

Hotels are **not** this list — a new shape whose information architecture differs needs its own screens, and
that is a bounded-context argument to be made on its merits, not a configuration change.

---

## 7 · What is still true and unfixed

| | |
|---|---|
| `AuthService.moduleFor()` | binary EDUCATION-vs-everything-else; assumes exactly two location registries forever |
| `Organization.type` | free text, no enum, no allow-list — the mismatch that caused the original routing bug |
| i18n | §5, unruled |

`Organization.type` being free text while routers enumerate is the root of this whole family. Worth its own
decision: either validate at the column, or commit to deriving everywhere. Half-and-half is what produced
the bug C2 fixed.
