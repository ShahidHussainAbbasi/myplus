# E6 — analysis: the case for it is smaller than the programme thought, and I checked

**Status:** ANALYSIS, shared for review. No design, no code — per `SAAS-BUILD-STANDARDS.md` §0 and
`CLAUDE.md` RULE 0.
**Programme:** [`saas-control-plane-review.md`](../saas-control-plane-review.md) — E6, the last item.
**Predecessors:** E1 · E2 · E3 · E4 · E5 · ONB-1/2/3 — all ✅ green.

Every figure below was measured on the running system on 2026-09-05, not carried over from the review.

---

## 1. Verdict up front

**E6 as framed is the weakest slice in the programme, and one of the three arguments for it is simply false.**

The review defined E6 as a *"server-rendered navigation manifest — replaces shipping 3,947 template lines to
every tenant"*, resting on three claims. Measured:

| The claim | Measured | |
|---|---|---|
| "3,947 template lines shipped whole" | **4,365 lines** — and it grew by 83 *during this analysis* | ✅ true, understated |
| "the browser should not decide this" | the server already refuses; hiding is cosmetic | ⚠ already true |
| a tenant is served what it cannot use | **57,286 bytes, 20.9% of the template** | ✅ true, and now quantified |
| *(my own hypothesis)* the hidden sections **flash** before being hidden | **disproved — see §4** | ❌ false |

The payload win is real but small: **7 KB for a general retailer, 42 KB for a pesticide dealer**, against a
page that gzips to 80 KB. Nothing on screen is wrong today. I would not spend a slice on this now — and §5
names two things in the same area that are worth more.

---

## 2. What is actually shipped

Three tenants, three different capability sets, one dashboard route:

```
owner.business@   306,822 bytes    43 data-capability nodes    2 of 13 capabilities off
owner.mobile@     306,206 bytes    43 data-capability nodes    9 of 13 off
owner.pesticide@  306,212 bytes    43 data-capability nodes    8 of 13 off
```

**The payload does not vary with what the tenant may do.** The ~600-byte spread is the tenant's own name and
figures. That part of the review is confirmed exactly.

Inside the template, measured with a depth-counted scan so a nested `<div>` cannot end a span early:

```
template bytes              273,791
bytes inside gated subtrees  57,286   (20.9%)
   in <div> sections         53,476   ← 93% of it
   in <li> nav items          2,875
   in <label> / <th> fields     935
```

**The navigation is 2,875 bytes of the 57,286.** A "navigation manifest" addresses 5% of the thing worth
addressing; the weight is in eleven whole `.formDiv` sections.

Per tenant, served-and-unusable — honouring the OR rule (`fieldSales,collections` hides only when **both**
are off):

| Tenant | Capabilities off | Bytes it can never use |
|---|---|---|
| `owner.business@` | 2 of 13 | **7,070** (2.6%) |
| `owner.mobile@` | 9 of 13 | **33,730** (12.3%) |
| `owner.pesticide@` | 8 of 13 | **41,937** (15.3%) |

Against 80 KB gzipped on the wire, the best case saves a few kilobytes of highly compressible markup.

---

## 3. Feasibility is not the problem — it is genuinely cheap

Worth stating, because it is the one thing that would have made this hard:

**The monolith can already resolve capabilities server-side with no network call.** `TokenStore` keeps the
access token in the session, and C3c put the tenant's capabilities in that token as the `caps` claim. So a
server-rendered manifest — or simply stamping `cap-off` at render time — needs no call to auth, no new
dependency, and no cache.

`CommerceDashboardController` currently puts exactly one thing in the model (`module`). Adding the capability
map is a few lines.

---

## 4. ⚠ The argument I expected to be strongest, and it is false

I predicted a flash: the gated markup ships **with no `cap-off` and no inline hide** (verified — **0 of 43**
nodes arrive hidden), and `capabilities.js` only applies `.cap-off` after `GET /getCapabilities` returns. A
mobile shop would therefore see *Prescriptions*, *Quarantine*, *Territory* and *Driver settlement* in its
sidebar on every load, then lose them.

**I checked instead of writing it down.** A probe that delayed `/getCapabilities` by three seconds and looked
at the page in that window found **zero gated nav items visible**, and the screenshot shows why:

* the sidebar groups (Register, Purchase, Sale, Till, Finance, Settings, Team) are **collapsed** by default,
  and all ten gated `<li>` items live inside them;
* the eleven gated sections are `.formDiv` — `display:none` until one is selected.

So the gated content is invisible for reasons that have nothing to do with capabilities, and the client-side
hide never shows. **There is no flash.** This was the strongest argument for E6 and it does not survive
contact with the page.

*(A user could in principle open a menu inside the ~25 ms window before the answer arrives. I am not counting
that as a defect.)*

---

## 5. ⭐ What the measurement found instead

### F-1 🟠 Four dashboards have NO capability gating at all

```
businessDashboard      4,365 lines   37 sections   43 gated
educationDashboard     3,411 lines   37 sections    0 gated
welfareDashboard         255 lines    4 sections    0 gated
agricultureDashboard     555 lines    4 sections    0 gated
appointmentDashboard     288 lines    5 sections    0 gated
```

Education is **3,411 lines and 37 sections** — within 20% of commerce — and **not one** of them is
capability-aware. Every education tenant is served every education feature, and the capability platform (C1..C6)
simply does not reach that screen.

This is a bigger version of the problem E6 was written to solve, in a module nobody has looked at. It is also
the honest reason the "assembled dashboard" idea has not paid off: it was only ever built for commerce.

### F-2 🟠 D-6 from E5 is still unbuilt, and it is an accountability hole

An audit record that fails to deliver twenty times is dropped silently, with no count anywhere and no way to
re-send. For a sale that is survivable. For a **support-access** record there is no second copy: the row *is*
the evidence that somebody looked at a customer's books. Eight are sitting in that state on the development
database right now, found only because a gate went red.

Measured against E6's few kilobytes, this is the better use of the next slice.

### F-3 🟢 The template is growing while unattended

`businessDashboard.html` went from 4,282 to 4,365 lines **during this analysis** — another session is editing
it. Whatever is decided, editing that file is currently a collision risk.

---

## 6. What I would do

**Close E6 as not-worth-building in its stated form**, and record why: the enforcement it was meant to add
already exists server-side, the flash it was meant to prevent does not happen, and the payload it was meant to
save is a few kilobytes of gzipped markup.

Then, in preference order:

1. **D-6** (F-2) — the undelivered-audit count and re-drive. Small, and it closes the last real hole in E4/E5.
2. **Capability-gate the education dashboard** (F-1) — the same 20% saving, on a screen with *zero* coverage,
   and it is a correctness point as much as a payload one.
3. **Defer the section markup** — if the payload is still wanted, the win is in the eleven `.formDiv`
   sections, not the navigation. That belongs to the front-end perf programme, which already owns lazy
   loading, rather than to the control plane.

If E6 is wanted anyway, the cheapest honest version is **stamp `cap-off` server-side from the `caps` claim**
(§3): a handful of lines, no new call, removes a round trip from every dashboard load, and makes the payload
match what the tenant may do. That is a fraction of the "navigation manifest" the programme imagined, and it
gets essentially all of the value.

---

## 7. What I did not check

* **Whether any tenant's browser is slow enough for the 25 ms window to matter.** No real-network measurement
  was taken; the flash was ruled out structurally, not on a slow connection.
* **Education's 37 sections against the education capability set** — I counted the gating (zero) but did not
  work out which sections *should* be gated. That is F-1's own analysis.
* **The other three commerce verticals** (`pharmaDashboard`, `marketplaceDashboard`) — they do not exist as
  templates; commerce is one dashboard, which is why the gating lives there.
