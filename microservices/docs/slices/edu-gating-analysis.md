# Capability gating for education — analysed, and I was wrong about it

**Status:** ANALYSIS. Recommends **not building it**, and corrects a claim I made three days ago.
**Origin:** [`e6-navigation-manifest-analysis.md`](e6-navigation-manifest-analysis.md) §5 F-1, where I called
this *"the largest remaining correctness item"*.
**Standard:** `CLAUDE.md` RULE 0 — which applies to my own claims as much as to the code.

---

## 1. Verdict, and a correction

**Do not build this yet.** The measurement that made it look urgent was true and the conclusion I drew from
it was not.

What I wrote in the E6 analysis:

> Education is **3,411 lines and 37 sections** — within 20% of commerce — and **not one** of them is
> capability-aware. Every education tenant is served every education feature.

Every word of that is still true. What I never checked was **how many education tenants there are**:

```
id  name                              type        plan  status
14  Owner Education's organization    EDUCATION   FREE  ACTIVE
24  Demo EDUCATION's organization     EDUCATION   FREE  ACTIVE
```

**Two, and both are seeded fixtures** — `owner.education@` and `demo.education@`. There are **no real
education customers.** "Every education tenant is served every education feature" is a sentence about two
dev fixtures.

Compare the case that justified the capability platform: **39 of 41 real commerce tenants** shown every
vertical at once, and a pesticide dealer looking at IMEI fields in every demo. That was a live complaint from
paying customers. This is not the same thing, and I presented it as though it were.

---

## 2. Why it is also more expensive than it looks

### The capability model has no education concept at all

Every capability and every shape is commerce:

```
batchTracking  expiryTracking  fefoAllocation  serialTracking  conditionGrading
looseSelling   rxRequired      fieldSales      journeyPlanning collections
installments   dealerPricing   bonusSchemes

shapes: general · retail · pharmacy · distribution · storefront
```

So gating education is not "add attributes to a template". It means **inventing an education capability axis**
— which of timetable, homework, behaviour, leave, meetings, notices, transport, exams, promotion, portals is
optional, and for whom — with **no customer to answer the question.** Two schools that differ are what tells
you where the axis goes; there is one of each fixture.

That is the framework-ahead-of-its-second-use that this programme has already rejected twice: R4 in the
control-plane review (*"a generic FieldPolicy with only two consumers"*), and E6's own analysis.

### ⚠ And a naive attempt would silently do nothing

`capabilities.js` loads from the shared header fragment, so it runs on the education dashboard already. Its
matching rule:

```js
// Unknown code => true. A capability this build does not know about must not blank a section on a
// tenant that is mid-upgrade; the server is the authority on the list, and it may be ahead of us.
return caps[code] === undefined ? true : caps[code] === true;
```

**Fails open, by design and correctly.** So tagging an education section `data-capability="homework"` compiles,
deploys, looks right in review — and hides nothing, for ever, until `homework` is added to the `Capability`
enum, the catalog, the shape presets and the seeder. It is the exact failure this codebase keeps paying for:
a mechanism that appears to work and is inert. Anyone attempting this without reading that line will believe
it is done.

---

## 3. What to do instead

**Record the architectural fact**, because the next person will otherwise assume `data-capability` works
everywhere. It does not: it is a commerce mechanism that happens to be loaded globally, and on any
non-commerce screen it is a no-op that looks like a control.

**Revisit when there is a second real education customer whose needs differ from the first's.** At that point
the axis is discovered rather than invented, which is how `Shape` and `Capability` were built for commerce in
the first place — from `owner.mobile@` and `owner.pesticide@` being genuinely different businesses.

**The trigger to watch:** an education tenant asking to hide something. That request names the capability.

---

## 4. What I checked, and what I did not

Checked:

* Both education orgs, by name and type — fixtures, and the student counts (111 and 7) confirm it.
* All 13 capabilities and all 5 shapes, read from the enums — none is education.
* `capabilities.js`'s matching rule, read rather than assumed — it fails open on an unknown code.
* Which optional education features carry any data at all: homework 0, leave 0, notices 0, substitution 1,
  meetings 1, vehicles 1, behaviour 29, exams 34, marks 16. **Even the fixtures barely use most of it**,
  which is another way of saying nobody has yet shown which parts a school would want gone.

Not checked:

* **Whether an education customer is in the pipeline.** If one is signing next month, this analysis changes —
  the work is the same size, it is the justification that moves. That is commercial knowledge I do not have,
  and it is the one input that would overturn this recommendation.
