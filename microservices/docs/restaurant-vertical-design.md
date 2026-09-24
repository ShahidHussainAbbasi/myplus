# RST — the restaurant vertical, on the existing commerce core

**Status (2026-09-24): DESIGN. R1 gate WRITTEN and GREEN 6/6 — and it found a design error, see §4.1.**
⚠ The R1 "no new capability" claim was WRONG and is corrected in §4.1: a restaurant cannot sell a
made-to-order item, because every sale line reserves stock unconditionally. Awaiting rulings on §3 and §9.
Raised 2026-09-24: a prospective tenant, *24/7 BBQ & Fast Food*, supplied a two-page printed menu.

Related: [capability platform](capability-platform-design.md) · [vertical profile](vertical-profile-any-business-design.md) · [commerce verticals blueprint](commerce-verticals-blueprint.md)

---

## 0 · ⚠ READ THIS FIRST — the menu is twice the size it was described as

The onboarding brief listed **6 categories** (Zingers, BBQ, Fast Food, Sandwiches, Fries, Rolls) and ~13
example items. That is **page 1 only**. The second photograph is a whole second page that the brief does not
mention at all.

| | Brief | Actual menu |
|---|---|---|
| Categories | 6 | **16** |
| Items | ~13 named | **87** |
| Composite items | not mentioned | **4 platters**, one with an either/or choice |
| Bought-in goods | not mentioned | **cold drinks** (1.5 L, 2.25 L) inside platters |

Scoping, pricing or demoing this tenant from the brief alone would under-build by half. The full transcription
is §2.

**This is not a criticism of the brief — it is the reason RULE 0 exists.** A printed menu is a business input,
not a spec, and the only reliable reading is the artefact itself.

---

## 1 · What already exists (surveyed, not assumed)

The proposal asks for a `RESTAURANT_FAST_FOOD` profile plus ~13 new capabilities. **The mechanism for exactly
that already exists and is load-bearing**, so most of the proposal is configuration, not construction.

### 1.1 The two axes are already built

`common-settings` holds `Shape` (what KIND of business — the information architecture) and `Capability` (what
the tenant may DO). `Shape.java`'s own javadoc states the rule this proposal must follow:

> *What must never appear here: a client's name. `if (organizationId == 24)` is the failure this whole
> mechanism exists to prevent. "Mobile shop" is not a shape — it is RETAIL plus serial tracking, condition
> grading and installments, which is precisely the point of having two axes.*

| Axis | Values today |
|---|---|
| `Shape` | GENERAL · RETAIL · PHARMACY · DISTRIBUTION · STOREFRONT |
| `Capability` | 13: batch/expiry/FEFO, serial, condition, loose, Rx, field sales, journey, collections, installments, dealer pricing, bonus |

`GENERAL` presets **every** capability, which is why every existing tenant is unaffected by any of this. A
tenant narrows only by deliberately choosing a shape, and an explicit override always beats the preset.

### 1.2 Reusable as-is — no restaurant work needed

| Need | What serves it |
|---|---|
| Menu items, categories, prices | Product + Category (catalog-service) |
| Fast order entry, tender, receipt | the POS sell screen, `SagaSellService` |
| Cash drawer, shift close | shift service, `pos.*` settings |
| Customers, phone, address | customer master + party-service |
| Purchases, suppliers, stock | purchase + inventory-service |
| Sales, payments, GL, food-cost reporting | finance-service (COGS already posts per sale) |
| Users, roles, permission sets | PERM-1 permission codes, role×location grants |
| Delivery, riders, settlement | OMS O7 D1–D6 (built and gated) |
| Order states, transitions | `FulfilmentStatus` — NEW → PACKED → SHIPPED → DELIVERED, with a legal-transition map |
| Per-order channel | B2B/B2C channel, derived from customerType |
| Multi-branch | multi-location stores/branches (role×location → JWT → scoping) |

### 1.3 The genuine gaps

| # | Gap | Why the core cannot cover it |
|---|---|---|
| G1 | **Order type** (dine-in / takeaway / delivery) | a sale has a channel, not a service mode. Three modes change the WORKFLOW, not the pricing |
| G2 | **Tables** and open tabs | a sale is atomic; a dine-in table is an order held open and added to over an hour |
| G3 | **Kitchen ticket / KDS** | nothing routes a line to a station or tracks prep state |
| G4 | **Modifiers** ("extra cheese", "no onion") | no per-line option model. Bonus/discount are money, not instructions |
| G5 | **Recipe / BOM** | selling a burger must consume a bun, not a burger |
| G6 | **Ingredient vs menu item** | one `Product` table, no notion of "not sellable at the counter" |
| G7 | **Composite items** (the 4 platters) | a platter is a bundle, and Platter 1 contains an either/or choice |
| G8 | **Waste / spoilage** | stock adjust exists (BLK-5) but has no waste reason or valuation |

⚠ **G5 is the one that changes the meaning of existing numbers.** Until recipes exist, a menu sale decrements
a *menu item* that was never purchased, so COGS for it is whatever `costPrice` happens to be — probably null.
See §7 for why that must be stated to the tenant rather than glossed.

---

## 2 · The menu, transcribed from the photographs

⚠ **Verify against the physical menu before import.** Transcribed from two photographs; ambiguities are
flagged and must be confirmed by the owner, not guessed.

**Page 1 — 24/7 BBQ & FAST FOOD · "GRILL. ROLL. REPEAT."**

| Zingers | Rs | Bar B Q | Rs | Fast Food | Rs |
|---|---|---|---|---|---|
| Zinger Burger | 350 | Chicken Tikka Leg | 330 | Crispy Broast Leg | **350?** ⚠ |
| Zinger Cheese Burger | 400 | Chicken Tikka Chest | 380 | Crispy Broast Chest | 400 |
| Jumbo Zinger | 500 | Chicken Malai Tikka | 500 | Full Broast | 1400 |
| Jumbo Cheese Zinger | 550 | Chicken Cheese Tikka | 450 | Crispy Mayo Broast Chest | 450 |
| Supreme Zinger | 250 | Chicken Charsi Tikka | 400 | Crispy Mayo Broast Leg | 400 |
| Junior Zinger Burger *(NEW)* | 220 | Chicken Bihari Tikka | 350 | Special Injected Broast | 500 |
| Chicken Burger | 250 | Chicken Green Tikka | 420 | Chatpata Broast Leg | 420 |
| Chicken Cheese Burger | 300 | Chicken Bihari Chest | 400 | Spicy Broast | 420 |
| Beef Burger | 280 | | | | |
| Beef Special Burger | 400 | | | | |
| Beef Cheese Burger | 330 | | | | |

| Sandwich | Rs | Fries | Rs | Rolls | Rs |
|---|---|---|---|---|---|
| Chicken Sandwich | 400 | Sada (Plain) Fries | 120 | Twister Roll | 220 |
| Club Sandwich | 350 | Masala Fries | 150 | Twister Cheese Roll | 270 |
| BBQ Sandwich | 400 | Cheese Fries | 200 | Twister Jumbo Roll | 420 |
| BBQ Club Sandwich | 400 | Mayo Fries | 170 | Chicken Roll | 180 |
| Chicken BBQ Cheese | 450 | Pizza Fries (Small) | 300 | Chicken Jumbo Roll | 350 |
| Crispy Sandwich | 400 | Pizza Fries (Large) | 600 | | |
| Cheese Sandwich | 400 | | | | |
| BBQ Club Cheese Sandwich | 450 | | | | |

Footer: **DINE IN · TAKE AWAY · HOME DELIVERY** · 0335-2456847 · 0310-4532754

**Page 2**

| Boti (Chicken) Plate | Rs | Beef Boti Plate | Rs | Beef Kabab | Rs |
|---|---|---|---|---|---|
| Chicken Boti | 400 | Beef Bihari Boti | 500 | Beef Bihari Kabab | 500 |
| Chicken Bihari Boti | 450 | Beef Special Boti | 500 | Beef Seekh Kabab (Plate) | 450 |
| Chicken Malai Boti | 450 | Beef Special Makhan Boti | 550 | Beef Gola Kabab | 500 |
| Chicken Cheese Boti | 500 | | | Beef Chimpta Kabab | 500 |
| Chicken Makhan Boti | 500 | | | Beef Special Fry Kabab | 500 |
| | | | | Beef Turkish Kabab | 550 |

| Chicken Kabab | Rs | Chicken Rolls | Rs | Beef Rolls | Rs |
|---|---|---|---|---|---|
| Chicken Reshmi Kabab | 450 | Chicken Chutney Roll | 180 | Beef Boti Roll | 200 |
| Chicken Cheese Kabab | 500 | Chicken Mayo Roll | 200 | Beef Jumbo Roll | 350 |
| Chicken Chimpta Kabab | 470 | Chicken Mayo Jumbo Roll | 270 | Beef Mayo Roll | 220 |
| Chicken Turkish Kabab | 520 | Chicken Cheese Roll | 230 | Beef Mayo Jumbo Roll | 370 |
| | | Chicken Cheese Jumbo Roll | 420 | Beef Kabab Roll | 170 |
| | | Chicken Kabab Roll | 150 | Beef Kabab Mayo Roll | 200 |
| | | **Chicken Malai Boti** ⚠ | 200 | | |
| | | Chicken Malai Mayo Roll | 220 | | |

| Specials | Rs | Tikka Biryani & Thali | Rs |
|---|---|---|---|
| Special Shapter Roll – 2 | 250 | Tikka Biryani | 650 |
| 24/7 Special Roll | 250 | Tikka Biryani Chest | 750 |
| Shapter Roll ⚠ | 600 | | |

**Platters** (composite — see G7)

| | Serves | Rs | Contents |
|---|---|---|---|
| Platter 1 | 2 | 1,200 | Chicken Tikka (Leg) · Chicken Bihari Boti (4) · Chicken Malai Boti (4) · Chicken Shish Kabab (2) **or** Beef Shish Kabab · Rice |
| Platter 2 | 2 | 800 | Chicken Tikka (Leg) · Chicken Bihari Boti (2) · Chicken Malai Boti (2) · Chicken Shish Kabab (1) · Beef Shish Kabab (1) · Rice |
| Platter 3 | 4–6 | 1,800 | Chicken Tikka (1 Chest + 1 Leg) · Bihari Boti · Malai Boti · Chicken Shish Kabab (2) · Beef Shish Kabab (2) · 2 Parathas + Rice · 1.5 L cold drink |
| Platter 4 | 6 | 2,500 | Chicken Tikka (1 Chest + 2 Leg) · Bihari Boti (10) · Chicken Shish Kabab (2) · Malai Boti + Beef Boti · Puri Paratha (3) with Rice · 2.25 L cold drink |

### 2.1 ⚠ Ambiguities — must be confirmed, never assumed

1. **Crispy Broast Leg** — printed `350` with `400` overwritten beside it. Unreadable. **Ask.**
2. **"Chicken Malai Boti" at 200 inside CHICKEN ROLLS** — every neighbour is a roll; this is almost certainly
   *Chicken Malai Boti Roll*. It also collides with *Chicken Malai Boti* at 450 in the Boti plate section, so
   importing both creates two products of the same name at different prices. **Ask.**
3. **"Shapter Roll" 600 vs "Special Shapter Roll – 2" 250** — a 2.4× spread on similar names. Possibly
   "Chapter"/"Shawarma". **Ask for the spelling and confirm the price.**
4. **Platter 1's "or"** — a customer CHOICE, so Platter 1 is not a fixed bundle (§G7).
5. **Cold drinks in Platters 3 and 4** — bought-in goods with real purchase cost, not kitchen output.
6. **Phone numbers** are the restaurant's own, for the receipt letterhead. **They are not customer records.**

---

## 3 · Proposed shape and capabilities

### 3.1 One new Shape, not a fork

```java
RESTAURANT("restaurant", "Restaurant / food service",
        EnumSet.of(Capability.MENU_MODIFIERS, Capability.ORDER_TYPES, Capability.KITCHEN_TICKETS))
```

The brief proposed ~13 capabilities. **Most are not capabilities.** Applying `Shape`'s own rule:

| Proposed | Verdict |
|---|---|
| `RESTAURANT_POS`, `MENU_MANAGEMENT`, `TAKEAWAY_ORDERS`, `DELIVERY_ORDERS`, `CUSTOMER_ORDERS`, `CASH_DRAWER` | **Not capabilities** — these are the core POS, catalog, OMS and shift features every tenant already has. Adding flags for them creates switches that can turn off things nothing can run without |
| `ORDER_MODIFIERS` → `MENU_MODIFIERS` | ✅ real capability |
| `DINE_IN_TABLES` → folded into `ORDER_TYPES` | a table is meaningless without dine-in; two flags that must agree are one flag |
| `KITCHEN_TICKETS` | ✅ real capability |
| `RECIPE_MANAGEMENT` + `INGREDIENT_INVENTORY` + `FOOD_COSTING` + `ADVANCED_RECIPE_COSTING` | **one capability, `RECIPES`** — they are inseparable. A recipe with no ingredient stock costs nothing; ingredient stock with no recipe consumes nothing |
| `WASTE_TRACKING` | ✅ real capability, useful beyond restaurants |
| `TABLE_RESERVATIONS`, `LOYALTY`, `ONLINE_ORDERING`, `DELIVERY_RIDER_MANAGEMENT`, `MULTI_BRANCH` | later, and three already exist (storefront, OMS O7, multi-location) |

**Net: 6 new capabilities, not 13** — five below, plus `MADE_TO_ORDER` which the R1 gate forced out (§4.1).

```
MADE_TO_ORDER     assembled on demand; skip the stock reservation (§4.1) — R1
ORDER_TYPES       dine-in / takeaway / delivery, and tables                  — R2
MENU_MODIFIERS    per-line options and kitchen instructions                  — R2
KITCHEN_TICKETS   route lines to stations; prep states                       — R2
RECIPES           menu item → ingredients; consumption and food cost         — R3
WASTE_TRACKING    spoilage with a reason and a value                         — R3
```

### 3.2 What the restaurant must NOT see

`Shape.RESTAURANT` presets these OFF, and the dashboard is assembled from capabilities, so they simply are not
there: FEFO, expiry batches, prescriptions, serial/IMEI, condition grading, dealer pricing, journey planning.

⚠ **But `EXPIRY_TRACKING` deserves a second look before it is switched off.** A kitchen holds perishable raw
meat and dairy, and the pharmacy floor rule exists because switching expiry off changes the stock engine. For
Phase R2 (ingredients) this may be wanted ON. Raised rather than decided — §9 Q7.

---

## 4 · Phasing — a tenant can start at any level

The requirement is that a user may begin basic or begin complete. Each phase is **independently shippable and
independently valuable**, and nothing in a later phase is required to run an earlier one.

### Phase R1 — take orders and get paid *(needs ONE capability — see §4.1)*

Menu as products, 16 categories, prices, POS, cash, receipt, daily sales.

⚠ **This section originally claimed R1 was "achievable on today's build with configuration only". That was
wrong, and the R1 gate caught it** — see §4.1.

| Delivers | Does not yet do |
|---|---|
| Ring up an order, tender, print | Order type (everything is one flow) |
| Daily sales, top items | Kitchen ticket |
| Customer name/phone for delivery | Ingredient stock or true food cost |

### 4.1 ⚠ MADE TO ORDER — the gap the R1 gate found

**A restaurant holds no finished Zinger Burgers.** It holds buns, fillets and oil, and assembles one when the
order lands. But `SagaSellService` builds a `StockReservationLine` for **every** sale line with no exemption,
so the platform cannot sell anything it does not physically hold. Running the R1 gate produced exactly that:

> `Not enough sellable stock — 'Zinger Burger': only 0 sellable, 2 requested.`

There is no service or non-stock product concept, and `BusinessSettingsCatalog` records that a
`pos.sale.negativeStockAllowed` toggle was **deliberately removed**, with a warning not to re-add one without
building the cross-service oversell path behind it. That rule is correct and hard-won for retail. It is wrong
in kind for a kitchen — and for every service business: a salon cannot stock a haircut.

**Both workarounds are bad.** Stocking phantom quantities of each menu item puts a lie in the inventory and
makes every stock report meaningless. Requiring recipes first collapses R1 into R3 and removes the
"start basic" offer entirely.

**So R1 needs one capability after all:**

```
MADE_TO_ORDER   this product is assembled on demand; skip the stock reservation
```

Per-product, not per-tenant — a restaurant still buys and stocks **cold drinks**, which must reserve
normally. It is also the capability a salon, a clinic and a repair shop need, so it is not restaurant-specific
and belongs in the shared catalogue.

⚠ **It must not become an oversell switch.** The exemption applies only to products flagged made-to-order,
never to stocked goods, or it silently re-opens the negative-stock path the platform removed on purpose.

**Gate:** `restaurant-menu-setup.cy.js` case 2b currently asserts the REFUSAL, because that is today's truth.
When `MADE_TO_ORDER` lands the case is **inverted, not deleted** — the refusal is the current behaviour, not
the desired one.

### Phase R2 — order types, tables, kitchen *(`ORDER_TYPES`, `KITCHEN_TICKETS`, `MENU_MODIFIERS`)*

Dine-in/takeaway/delivery; open tables; KOT print or screen; per-line modifiers; prep states; order-type
reporting.

### Phase R3 — recipes and true food cost *(`RECIPES`, `WASTE_TRACKING`)*

Ingredients as non-sellable items; recipe versions; ingredient consumption on sale; real COGS per menu item;
waste with reason and value; low-stock alerts on ingredients.

### Phase R4 — growth

Online ordering (storefront exists), rider management (OMS O7 exists), loyalty, reservations, combos,
multi-branch (exists), central kitchen.

**⚠ The honesty rule across phases.** On R1 and R2, ingredient stock is NOT tracked and food cost is NOT
known. The screens must say so — a "Food cost" widget reading 0 or a margin computed from a null cost is
worse than no widget, and this platform has already paid for that exact failure twice (COGS-1, COGS-2). See §7.

---

## 5 · Data model (proposed)

### 5.1 Menu item vs ingredient — one flag, not a second table

`Product` gains `itemRole`: `MENU` (sellable at the counter, default) · `INGREDIENT` · `SUPPLY`.
The POS picker and menu screens filter to `MENU`; purchases and recipes see all three. A second table would
fork every read that already works — pricing, stock, purchase, reporting.

### 5.2 Recipe

```
recipe(id, org_id, product_id, version, effective_from, effective_to, created_by)
recipe_line(recipe_id, ingredient_product_id, quantity, unit, wastage_pct)
```

⚠ **Versioned, and the version is STAMPED ON THE SALE LINE** — the same rule `packSizeSnapshot` follows for
loose selling. A recipe change must never restate what an old sale cost. Without the stamp, raising the
chicken portion from 120 g to 140 g silently re-costs every burger ever sold.

### 5.3 Order type and table

On `CustomerHistory` (the invoice): `orderType` (DINE_IN/TAKEAWAY/DELIVERY), `tableId`, `kitchenStatus`.
⚠ Kitchen status is **separate from payment status** — the brief is right about this, and the existing
`FulfilmentStatus` already models order progress independently of money.

### 5.4 Modifiers

```
modifier_group(id, org_id, name, min_select, max_select, required)
modifier_option(group_id, name, price_delta, recipe_delta_json, kitchen_note)
product_modifier_group(product_id, group_id)
sell_line_modifier(sell_id, option_id, price_delta_applied, name_snapshot)
```

⚠ `name_snapshot` and `price_delta_applied` are stored, not resolved at read — the receipt for a sale made in
March must still say what was added and what it cost, even if the option is renamed or repriced in June.

### 5.5 Composite items (platters)

A platter is a `MENU` product whose recipe lines point at other `MENU` products rather than ingredients.
Platter 1's "or" is a **modifier group** with `min_select=1, max_select=1`, not a fixed line.

---

## 6 · Roles

Maps onto PERM-1 permission sets — no new mechanism.

| Role | Sees |
|---|---|
| Owner | everything |
| Restaurant manager | operations, menu, staff, reports, approvals |
| Cashier | POS, payments, receipts |
| Waiter | tables, order entry, notes — **no prices on the kitchen view** |
| Kitchen | KOT/KDS only — ⚠ **no prices, no margin, no customer credit** |
| Storekeeper | ingredients, purchases, counts, waste |
| Rider | assigned deliveries only (OMS O7 role exists) |
| Accountant | sales, payments, expenses, supplier balances |

---

## 7 · ⚠ The honesty rule — what this platform has already learned

Three defects in the last month were all the same shape: **a number that was wrong while everything
downstream looked healthy.**

| | What happened |
|---|---|
| COGS-1 | cost divided by REMAINING quantity — 20× inflation; a balanced trial balance hid it |
| COGS-2 | a costless batch silently dropped from COGS — profit reported 330 where 80 was right |
| U15-A4 | a loose scan priced pieces at the pack price — the cashier handed back the wrong change |

A restaurant multiplies this risk, because food cost is the number owners manage the business by. So:

1. **Before recipes exist, do not show a food-cost or margin figure at all.** Not zero, not blank-with-a-
   percentage — absent, with one line saying why.
2. **A menu item with no recipe must not report 100% margin.** That is COGS-2 wearing a chef's hat.
3. **Ingredient stock must not be claimed as accurate until portions are configured and counted.**

---

## 8 · Cypress gates

Per phase, each asserting what the defect would break:

| Phase | Spec | Must fail before |
|---|---|---|
| R1 | `restaurant-menu-setup.cy.js` | 16 categories and 87 items import; a platter is not importable as a flat price |
| R1 | `restaurant-shape.cy.js` | choosing RESTAURANT hides FEFO/expiry/Rx/IMEI; GENERAL tenants unaffected |
| R2 | `restaurant-order-types.cy.js` | an order carries its type; dine-in holds a table open; totals split by type |
| R2 | `restaurant-kitchen.cy.js` | a line routes to its station; prep state moves independently of payment |
| R2 | `restaurant-modifiers.cy.js` | "extra cheese +80" reaches the line, the receipt and the kitchen note |
| R3 | `restaurant-recipe-costing.cy.js` | ⭐ selling one burger consumes its ingredients; **an old sale keeps its old recipe's cost** |
| R3 | `restaurant-no-recipe-honesty.cy.js` | ⭐ a menu item with no recipe shows NO margin, not 100% |

---

## 9 · Questions that must be answered before go-live

Ambiguities from §2.1, plus:

1. Crispy Broast Leg — 350 or 400?
2. "Chicken Malai Boti" in the rolls list — is it a roll? What distinguishes it from the 450 plate item?
3. "Shapter Roll" — correct spelling, and is 600 right beside 250?
4. Do menu prices **include** tax, and is the tenant tax-registered?
5. Dine-in — how many tables? Or is it counter-service only?
6. Delivery — own riders or a platform? Delivery charge? Radius?
7. **Should `EXPIRY_TRACKING` be ON for raw meat and dairy in R2?** (§3.2)
8. Kitchen printer or screen? How many stations?
9. One branch or more?
10. Do they want recipes now, or start at R1 and add later?
11. Combos/deals beyond the four platters?
12. Customer credit for regulars, or cash/card only?
13. Shift open/close per cashier?
14. Which of the two phone numbers prints on the receipt?
