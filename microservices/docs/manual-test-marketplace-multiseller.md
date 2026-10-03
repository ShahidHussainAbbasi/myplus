# Manual test cases: multi-seller marketplace (MKT)

**Generated** from [`marketplace/manual-cases.json`](marketplace/manual-cases.json) by `marketplace/rtm.py`. Edit the JSON, not this file. The same cases are on the [published manual-testing page](https://claude.ai/artifact/U1FyPcwSxYsg4pGLsyFzq8), where testers record Pass / Fail / Blocked. Per GATE-RUNBOOK §6 they also belong in the product Test Book.

**How to read a case.** Who, what must be true before, then numbered steps — each one action and the result that proves it — and a cleanup that puts the test data back. Cases of built slices are RECORDED: `cypress/e2e/marketplace/walk/mkt-walk.cy.js` performs every step on a live stack, asserts its expected result and captures the screen (the pictures are on the published page). Cases of slices not built yet are written from the design. ⚠ marks a case that guards a known trap: repeat it after any nearby change.

| Account | Password | Plays |
|---|---|---|
| `owner.business@myplus.com` | `Demo@2025!` | Seller A (retail counter; admin./user. ladder in the same org) |
| `owner.mobile@myplus.com` | `Demo@2025!` | Seller B (mobile shop: the second seller of the same phone) |
| `owner.audit@myplus.com` | `Demo@2025!` | a shop that has never applied (reset with walk-reset.sql) |
| `owner.pesticide@myplus.com` | `Demo@2025!` | a shop MaxTheService never entitled |
| `owner.pharma@myplus.com` | `Demo@2025!` | a pharmacy (prescription control on) |
| `user.business@myplus.com` | `Demo@2025!` | a user-tier member of Seller A |
| `admin@myplus.com` | `Admin@2025!` | MaxTheService operator |


## MKT-0a

### M-0a-01 Marketplace is off for a shop that never opted in

**Who:** owner.pesticide@myplus.com — a shop MaxTheService never entitled  
**Before:** Nobody has switched the marketplace on for this shop.  
**Covers:** MKT-R20.0  
**Evidence:** recorded 2026-10-03 22:31 UTC  
**Automated by:** MKT-0a-02

| # | Do this | Expect |
|---|---|---|
| 1 | Log in as owner.pesticide@myplus.com (password Demo@2025!). Open Sale in the left menu. | The Sale menu has no "Marketplace" entry. |
| 2 | Open Settings → Configuration and find "Sell on the MaxTheService marketplace". | The switch is OFF. Its help text reads "List your products beside other sellers on the MaxTheService marketplace. MaxTheService approves your seller account first. Off until you switch it on." |
| C1 | Nothing was changed. | Nothing to undo. |

### M-0a-02 The owner cannot switch it on before MaxTheService entitles the shop

**Who:** owner.pesticide@myplus.com  
**Before:** As M-0a-01: the shop is on the FREE plan and not entitled.  
**Covers:** MKT-R20.0, MKT-R22.1  
**Evidence:** recorded 2026-10-03 22:31 UTC  
**Automated by:** MKT-0a-02, MKT-0a-03

| # | Do this | Expect |
|---|---|---|
| 1 | Settings → Configuration → try to tick "Sell on the MaxTheService marketplace". | The switch is greyed out and cannot be ticked. Under it a "Not in plan" badge with a lock says why. No error page. |
| 2 | Developer tools (or the API): POST /mkt/acceptAgreement {"version":"v1","displayName":"Should not exist"}, then GET /mkt/seller. | The POST is refused: "… not switched on …". The GET answers and shows no seller account (account: null) — the refused write created nothing. |
| C1 | Nothing was saved. | Nothing to undo. |

> **Found by the walk:** Case text corrected: for a shop that is not entitled the switch is greyed out with a "Not in plan" badge — it was described as "refused on save".

### M-0a-03 The owner switches it on, reads the agreements and applies

**Who:** admin@myplus.com (operator), then owner.audit@myplus.com (a shop that has never applied)  
**Before:** owner.audit@'s shop has never applied (on a test environment run walk-reset.sql first) and is not entitled.  
**Covers:** MKT-R20.0, MKT-R9.1, MKT-R20.1, MKT-R9.2, MKT-R9.3  
**Evidence:** recorded 2026-10-03 22:31 UTC  
**Automated by:** MKT-0a-01, MKT-0a-05

| # | Do this | Expect |
|---|---|---|
| 1 | As admin@myplus.com: Platform → search "Owner Audit" → open the shop → "Sell on the MaxTheService marketplace" → Grant → reason "marketplace pilot" → Grant. | The row reads "Entitled". |
| 2 | Log in as owner.audit@myplus.com. Settings → Configuration → tick "Sell on the MaxTheService marketplace". | "Saved". The switch stays ON. |
| 3 | Log out and back in (the switch travels with the login). Sale → Marketplace. | Status lines read "Marketplace selling is switched on." and that the agreements are not yet accepted. The agreement box shows what is shared ("product identity, your marketplace price, availability, delivery area and time, warranty, return policy, and your business name") and what is never asked for ("what you paid suppliers, your margins, your other customers, your staff's data, or your full stock history"). "Accept and apply" is greyed out. |
| 4 | Type "Audit Electronics" as the name customers will see. Tick "I have read both agreements and accept them for this business." | "Accept and apply" becomes active. |
| 5 | Click "Accept and apply". | The lines read "Agreements accepted (version v1)." and "MaxTheService is reviewing your seller account." The agreement box closes. |
| C1 | None yet: M-0a-06 continues with this application and undoes both switches at its end. | The application waits in the operator's review list. |

### M-0a-04 A user-tier member cannot accept agreements for the business

**Who:** user.business@myplus.com (a staff member of Shahzad Mobile Shop, user tier)  
**Before:** Shahzad Mobile Shop (owner.business@) has the marketplace switched on.  
**Covers:** MKT-R22.1  
**Evidence:** recorded 2026-10-03 22:31 UTC  
**Automated by:** MKT-0a-04

| # | Do this | Expect |
|---|---|---|
| 1 | Developer tools: as user.business@myplus.com, POST /mkt/acceptAgreement {"version":"v1","displayName":"x"}. | Refused: "Only the owner or an admin can accept these agreements for the business." Nothing is accepted. |
| C1 | Nothing was changed. | Nothing to undo. |

### M-0a-05 ⚠ The customer-facing policies exist before the first live order (go-live check)

**Who:** admin@myplus.com (operator)  
**Before:** Before switching the marketplace on for customers.  
**Covers:** MKT-R20.0, MKT-R1.3  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Open /marketplace and look for the links to the customer terms, returns and refunds, complaint process and cash-on-delivery terms. | Each link opens a page with a version, an effective date and text; none is blank. The customer terms name MaxTheService as the marketplace operator and its legal entity. |
| 2 | Operator console → Marketplace policies: list the commission, warranty and return policies. | At least one active commission policy marked "default", one warranty and one return policy exist (M-1c-00). |
| C1 | Nothing to undo. | — |

### M-0a-06 Only MaxTheService approves a seller, and a suspension says why

**Who:** admin@myplus.com (operator), then owner.audit@myplus.com  
**Before:** owner.audit@ has applied as "Audit Electronics" (M-0a-03).  
**Covers:** MKT-R20.1, MKT-R22.1, MKT-R19.1  
**Evidence:** recorded 2026-10-03 22:32 UTC  
**Automated by:** MKT-0a-06, MKT-0a-07

| # | Do this | Expect |
|---|---|---|
| 1 | As admin@myplus.com: Platform → "Marketplace sellers" (opens on "Waiting for review"). | "Audit Electronics" is listed with Approve, Reject and a reason box. |
| 2 | Click Approve on "Audit Electronics", then open the "Approved" list. | It has left "Waiting for review". On "Approved" its row offers only Suspend (never Approve or Reject). |
| 3 | Click Suspend with the reason box empty. | Refused: "Give the seller a reason. They will see it as written." The seller stays Approved. |
| 4 | Type "documents expired" in the reason box and click Suspend. | The row leaves "Approved" (it is now under "Suspended"). |
| 5 | As owner.audit@myplus.com: Sale → Marketplace. | The account line reads "Suspended: documents expired". |
| 6 | As admin@myplus.com: Marketplace sellers → "Suspended" → Reinstate on "Audit Electronics". | The row leaves "Suspended". |
| 7 | As owner.audit@myplus.com: Sale → Marketplace. | The account line reads "Approved by MaxTheService. You can list products." |
| C1 | As owner.audit@: Settings → Configuration → untick "Sell on the MaxTheService marketplace". | "Saved"; the Marketplace entry leaves the Sale menu at the next login. |
| C2 | As admin@: Platform → Owner Audit → "Sell on the MaxTheService marketplace" → Revoke → reason "walk finished" → Revoke. | The row reads "Revoked". The seller account and the accepted agreement stay: they are records, never deleted. To walk M-0a-03 again on a test environment, run walk-reset.sql. |


## MKT-1b

### M-1b-01 A seller proposes a phone for the marketplace

**Who:** owner.business@myplus.com (Shahzad Mobile Shop)  
**Before:** Shahzad Mobile Shop is an approved seller and has a product "Galaxy A32 128 Black W93787" with 5 in stock (add it under Products if it is missing).  
**Covers:** MKT-R5.1, MKT-R6.2, MKT-R6.4, MKT-R5.2  
**Evidence:** recorded 2026-10-03 22:32 UTC  
**Automated by:** MKT-1b-01, MKT-1b-02

| # | Do this | Expect |
|---|---|---|
| 1 | Log in as owner.business@myplus.com. Sale → Marketplace → "Propose a product". | The proposal form opens with: Your product, Brand, Model, Variant / storage, Colour, Condition and Warranty. |
| 2 | Your product "Galaxy A32 128 Black W93787". Brand "Samsung". Model "Galaxy A32 W93787". Variant / storage "128 GB" (with the space). Colour "black" (lower case). Condition New. Click "Send for review". | "Sent to MaxTheService for review." A new row shows the identity SAMSUNG\|GALAXY-A32-W93787\|128GB\|BLACK\|NEW… (the space and the lower case are normalised by the server) and a yellow "Waiting for review" badge. |
| C1 | None: M-1b-02 continues with this proposal. | It waits in the operator's "Product matching" queue. |

> **Found by the walk:** DEFECT, fixed: the identity key glued a model code to the number before it ("Galaxy A32 W70934" → GALAXY-A32W70934, "W" read as watts). A unit now has to end the word (ProductIdentityKeyTest).

### M-1b-02 The operator matches it: one marketplace product is born

**Who:** admin@myplus.com (operator)  
**Before:** M-1b-01 done.  
**Covers:** MKT-R6.4, MKT-R5.2, MKT-R6.5  
**Evidence:** recorded 2026-10-03 22:32 UTC  
**Automated by:** MKT-1b-03

| # | Do this | Expect |
|---|---|---|
| 1 | As admin@myplus.com: Platform → "Product matching". | Shahzad Mobile Shop's proposal SAMSUNG\|GALAXY-A32-W93787\|128GB\|BLACK\|NEW… is listed. Its "Existing match" column reads "New marketplace product". |
| 2 | Click Match on that row. | The row leaves the queue. |
| 3 | Open the "Matched" list. | The proposal is listed there, now attached to a marketplace product. |
| C1 | None: the matched product is what M-1b-03 and the offers in MKT-1c build on. | — |

### M-1b-03 The same phone from a second seller is suggested, never merged on its own

**Who:** owner.mobile@myplus.com (Mobile Distributor), then admin@myplus.com  
**Before:** M-1b-02 done. Mobile Distributor is an approved seller with a product "Samsung A-32 128GB blk W93787" in stock.  
**Covers:** MKT-R6.1, MKT-R5.4, MKT-R6.4  
**Evidence:** recorded 2026-10-03 22:32 UTC  
**Automated by:** MKT-1b-04

| # | Do this | Expect |
|---|---|---|
| 1 | As owner.mobile@myplus.com: Sale → Marketplace → "Propose a product". Your product "Samsung A-32 128GB blk W93787". Brand " samsung " (spaces), Model "galaxy a32 w93787", Variant "128 gb", Colour "BLACK", Condition New. Send for review. | The row shows the SAME identity as Shahzad's, SAMSUNG\|GALAXY-A32-W93787\|128GB\|BLACK\|NEW…, and "Waiting for review". Nothing is live yet. |
| 2 | As admin@myplus.com: Platform → "Product matching". | Mobile Distributor's row reads "Same identity as product #<the product from M-1b-02>" in "Existing match". |
| 3 | Click Match on it. | Both sellers now point at ONE marketplace product (the "Matched" list shows both rows with the same product). |
| C1 | None: MKT-1c offers this product from both sellers. | — |

### M-1b-04 64GB is never merged with 128GB

**Who:** owner.mobile@myplus.com, then admin@myplus.com  
**Before:** M-1b-02 done (the 128GB product exists).  
**Covers:** MKT-R6.6, MKT-R6.1  
**Evidence:** recorded 2026-10-03 22:32 UTC  
**Automated by:** MKT-1b-05

| # | Do this | Expect |
|---|---|---|
| 1 | As owner.mobile@myplus.com: propose "Galaxy A32 64 Black W93787" with Brand Samsung, Model "Galaxy A32 W93787", Variant "64GB", Colour Black, New. | The identity ends …\|64GB\|BLACK\|NEW…; "Waiting for review". |
| 2 | As admin@myplus.com: Platform → "Product matching". | The 64GB row reads "New marketplace product" — never "Same identity as product #…". |
| C1 | As admin@: on the 64GB row type "walk cleanup" in the note box and click Reject. | The row leaves the queue; no marketplace product is created for it. |

### M-1b-05 The operator corrects a bad match; the seller reads the note

**Who:** admin@myplus.com, then owner.business@myplus.com  
**Before:** M-1b-02 done.  
**Covers:** MKT-R6.5, MKT-R6.4  
**Evidence:** recorded 2026-10-03 22:33 UTC  
**Automated by:** MKT-1b-06

| # | Do this | Expect |
|---|---|---|
| 1 | As admin@myplus.com: Product matching → "Matched". On Shahzad Mobile Shop's row click "Needs correction" with the note box empty. | Refused: "Tell the seller what is wrong…". The row stays Matched. |
| 2 | Type "colour is Blue on the box" in the note box and click "Needs correction". | The row leaves "Matched". |
| 3 | As owner.business@myplus.com: Sale → Marketplace. | The proposal shows a red "Needs correction" badge and the note exactly as typed: "colour is Blue on the box". |
| C1 | As owner.business@: propose "Galaxy A32 128 Black W93787" again with the same details; as admin@: Product matching → Match it. | Shahzad Mobile Shop is attached to the product again, ready for MKT-1c. |

### M-1b-06 A product from another shop's catalogue cannot be proposed

**Who:** owner.mobile@myplus.com, with the browser's developer tools  
**Before:** M-1b-01 done; Shahzad Mobile Shop's product id is known.  
**Covers:** MKT-R22.1  
**Evidence:** recorded 2026-10-03 22:33 UTC  
**Automated by:** MKT-1b-07

| # | Do this | Expect |
|---|---|---|
| 1 | As owner.mobile@myplus.com: POST /mkt/proposeProduct with sourceProductId = Shahzad Mobile Shop's product id, Brand Samsung, Model "Galaxy A32 X". | Refused: "That product is not in your catalogue." — the same sentence an id that does not exist gets. |
| C1 | Nothing was created. | Nothing to undo. |

### M-1b-07 Pack sizes stay separate products (Panadol 10s and 20s)

**Who:** owner.pharma@myplus.com (a pharmacy, approved seller)  
**Before:** The pharmacy has products "Panadol Extra 10s W93787" and "Panadol Extra 20s W93787" (not prescription-only).  
**Covers:** MKT-R6.6, MKT-R6.3  
**Evidence:** recorded 2026-10-03 22:33 UTC  
**Automated by:** ProductIdentityKeyTest

| # | Do this | Expect |
|---|---|---|
| 1 | As owner.pharma@myplus.com: Sale → Marketplace → Propose a product → "Panadol Extra 10s W93787", Brand GSK, Model "Panadol Extra W93787", Size "500 mg", Pack size "10". Send for review. | Sent. The identity ends in the pack size 10. |
| 2 | Propose "Panadol Extra 20s W93787" the same way with Pack size "20". | A second row with a DIFFERENT identity ending in 20. The two are never suggested as the same product. |
| C1 | As admin@: Product matching → Reject both Panadol rows with the note "walk cleanup". | Both leave the queue. |


## MKT-1c

### M-1c-00 The operator creates the policies sellers choose from

**Who:** admin@myplus.com (operator)  
**Before:** MKT-1c is deployed.  
**Covers:** MKT-R14.1, MKT-R14.2, MKT-R7.4  
**Evidence:** recorded 2026-10-03 22:33 UTC  
**Automated by:** MKT-1c-09, MKT-1c-10

| # | Do this | Expect |
|---|---|---|
| 1 | As admin@myplus.com: Platform → "Marketplace policies". | The policy form (Kind, Name sellers see, …, Create policy) and the policy list are shown, with the line "Policies are never edited. To change terms, create a new policy and deactivate the old one, so nothing already sold changes." |
| 2 | Kind "Warranty", leave "Warranty provider" EMPTY, Name "No provider", Months 12. Click "Create policy". | Refused: "Name the warranty provider. MaxTheService is never assumed to be it." Nothing is added. |
| 3 | Kind "Warranty". Name "12 months — authorised distributor W93787". Provider "Samsung Pakistan (authorised distributor)". Months 12. Covers "Manufacturing defects". Excludes "Physical and liquid damage". Create policy. | Created. The list shows WARRANTY · the name · "12 · Samsung Pakistan (authorised distributor) · Manufacturing defects", with Deactivate and NO Edit. |
| 4 | Kind "Returns". Name "7 days W93787". Return days 7. Create policy. | Listed as RETURN · "7 days". |
| 5 | Kind "Commission". Name "Standard 8% W93787". Charged on "Item price (not delivery)". Rate 8. Tick "Use for newly approved offers". Create policy. | Listed as "COMMISSION · default" with "8% · ITEMS". It replaces the earlier default for offers approved from now on. |
| C1 | None: the offers in MKT-1c use these. M-1c-07 deactivates the return policy. | — |

### M-1c-01 A seller creates an offer and sends it for approval

**Who:** owner.business@myplus.com (Shahzad Mobile Shop)  
**Before:** Shahzad Mobile Shop is attached to "Samsung Galaxy A32 W93787 128GB Black" (M-1b-02, M-1b-05) and has 5 in stock. M-1c-00 done.  
**Covers:** MKT-R5.3, MKT-R7.5, MKT-R14.1  
**Evidence:** recorded 2026-10-03 22:33 UTC  
**Automated by:** MKT-1c-01

| # | Do this | Expect |
|---|---|---|
| 1 | As owner.business@myplus.com: Sale → Marketplace → "New offer". | The offer form opens: Product (only products MaxTheService matched), Price (Rs.), Delivery in (hours), Cities you deliver to, Warranty, Returns. |
| 2 | Product "Samsung Galaxy A32 W93787 128GB Black". Price 52000. Delivery in 24 hours. Cities "Karachi, karachi , Lahore". Open the Warranty and the Returns lists. | Only ACTIVE policies are offered, among them "12 months — authorised distributor W93787" and "7 days W93787". There is no way to type a warranty of your own. |
| 3 | Warranty "12 months — authorised distributor W93787", Returns "7 days W93787". Click "Save and send for approval". | The new row shows Rs. 52,000, cities "Karachi, Lahore" (the duplicate dropped, the spelling kept as typed) and a yellow "Waiting for review" badge. The whole table, Edit and Pause included, fits the screen without scrolling sideways. |
| C1 | None: the offer continues in M-1c-04. (The server also refuses "send for approval" without both policies — gate MKT-1c-01.) | — |

> **Found by the walk:** DEFECT, fixed: on a 1366 px screen "My offers" (944 px) and the proposals table (923 px) overflowed a 900 px box, hiding Edit and Pause behind a sideways scroll. Headers now wrap; the walk asserts the table fits.

### M-1c-02 The browser cannot choose who owns or ships the stock

**Who:** owner.business@myplus.com, with the browser's developer tools  
**Before:** M-1c-01 done; the offer id is known.  
**Covers:** MKT-R3.1, MKT-R4.1, MKT-R22.1, MKT-R20.2, MKT-R3.2  
**Evidence:** recorded 2026-10-03 22:33 UTC  
**Automated by:** MKT-1c-02, MKT-1c-04

| # | Do this | Expect |
|---|---|---|
| 1 | POST /mkt/saveOffer {id: <the offer>, sellerOrganizationId: 999999, stockOwnerOrganizationId: 999999, fulfillerOrganizationId: 999999}. | Saved, but every party is still Shahzad Mobile Shop's own organisation and the price is still 52,000 (an edit keeps what it does not mention). |
| 2 | POST /mkt/saveOffer {id: <the offer>, stockSourceType: "SUPPLIER"}. | Refused: "Offers from supplier stock are not available yet." The source stays MERCHANT. |
| C1 | Nothing changed. | Nothing to undo. |

### M-1c-03 A prescription product cannot be offered

**Who:** owner.pharma@myplus.com (a pharmacy, approved seller)  
**Before:** The pharmacy has a product "Augmentin W93787" with "Prescription required" ticked (Clinical & Safety).  
**Covers:** MKT-R20.2, MKT-R7.6  
**Evidence:** recorded 2026-10-03 22:34 UTC  
**Automated by:** MKT-1c-03, MKT-1b-09

| # | Do this | Expect |
|---|---|---|
| 1 | As owner.pharma@myplus.com: Sale → Marketplace → "Propose a product" → "Augmentin W93787", Brand GSK, Model "Augmentin W93787". Send for review. | Refused: "Prescription and restricted products cannot be sold on the marketplace yet." Nothing is added to the proposals table. |
| C1 | Nothing was created. | Nothing to undo. |

### M-1c-04 The operator approves; a price outside the limits is refused

**Who:** admin@myplus.com (operator), then owner.business@myplus.com  
**Before:** M-1c-01 done: the offer waits for review. A default commission policy exists (M-1c-00).  
**Covers:** MKT-R7.4, MKT-R22.2  
**Evidence:** recorded 2026-10-03 22:34 UTC  
**Automated by:** MKT-1c-05

| # | Do this | Expect |
|---|---|---|
| 1 | As admin@myplus.com: Platform → "Offer approvals" (opens on "Waiting for review"). | Shahzad Mobile Shop's offer for "Samsung Galaxy A32 W93787 128GB Black" is listed at 52,000 for "Karachi, Lahore", with Approve and Reject. |
| 2 | Click Approve. | The row leaves the queue. |
| 3 | As owner.business@myplus.com: Sale → Marketplace → My offers. | The offer reads "Live". |
| 4 | Developer tools, as admin@: POST /platform/mkt/productLimits {id: <the marketplace product>, priceFloor: 40000, priceCeiling: 60000}. | Saved. |
| 5 | As owner.business@: My offers → Edit on the offer → Price 75000 → "Save". | Refused: "This offer's price is outside the allowed range…". The price stays 52,000. |
| 6 | Change the price to 51500 and click "Save". | Saved; the row shows 51,500. |
| C1 | As owner.business@: Edit → Price 52000 → Save. | The row shows 52,000 again (later cases expect it). |

### M-1c-05 Suspending a seller takes their offers down at once

**Who:** admin@myplus.com (operator) and a customer in an incognito window  
**Before:** M-1c-04 done: the offer is Live.  
**Covers:** MKT-R7.6  
**Evidence:** recorded 2026-10-03 22:34 UTC  
**Automated by:** MKT-1c-07

| # | Do this | Expect |
|---|---|---|
| 1 | Customer (incognito): open /marketplace?product=<the product>&city=Karachi. | Shahzad Mobile Shop is listed with Rs. 52,000. |
| 2 | As admin@myplus.com: Marketplace sellers → "Approved" → Shahzad Mobile Shop → reason "documents expired" → Suspend. | The row leaves "Approved". |
| 3 | Customer: reload the product page. | Shahzad Mobile Shop's offer is gone. |
| 4 | As admin@: "Suspended" → Reinstate Shahzad Mobile Shop. Customer: reload. | The offer is back. |
| 5 | Customer: change the city in the address to Quetta (…&city=Quetta). | The offer is not listed: Shahzad delivers to Karachi and Lahore only. |
| C1 | Nothing left to undo: the seller was reinstated in step 4. | — |

### M-1c-06 Customers see the provider's warranty and nothing internal

**Who:** Customer (incognito window), developer tools  
**Before:** M-1c-04 done: the offer is Live.  
**Covers:** MKT-R9.2, MKT-R9.3, MKT-R14.2, MKT-R14.1  
**Evidence:** recorded 2026-10-03 22:34 UTC  
**Automated by:** MKT-1c-06, MKT-1c-09

| # | Do this | Expect |
|---|---|---|
| 1 | Open /marketplace/public/products/<the product>/offers?city=Karachi and read Shahzad Mobile Shop's row. | warrantyProvider "Samsung Pakistan (authorised distributor)", warrantyMonths 12, returnDays 7. No cost, margin, purchase, supplier or stock-movement field, and no organisationId other than the seller's own (sellerOrganizationId is public, by design). |
| C1 | Nothing changed. | Nothing to undo. |

### M-1c-07 Deactivating a policy never changes what was sold

**Who:** admin@myplus.com, then owner.business@myplus.com  
**Before:** The offer uses "7 days …" (M-1c-01).  
**Covers:** MKT-R7.4, MKT-R13.3  
**Evidence:** recorded 2026-10-03 22:34 UTC  
**Automated by:** MKT-1c-10

| # | Do this | Expect |
|---|---|---|
| 1 | As admin@myplus.com: Marketplace policies → "7 days W93787" → Deactivate. | The row turns grey and loses its Deactivate button. |
| 2 | As owner.business@: My offers → Edit on the offer. Open the Returns list. | "7 days W93787" is no longer offered. |
| 3 | Developer tools: GET /mkt/getOffer?id=<the offer>, then POST /mkt/saveOffer {id: <the offer>, returnPolicyId: <the "7 days W93787" id>}. | The offer still names the 7-day policy (what was agreed stays). Choosing it again is refused: "Choose an active return policy." |
| C1 | None: a deactivated policy is never re-activated. Create a new one when terms change. | — |

> **Found by the walk:** DEFECT, fixed: the operator's Deactivate ignored the server's answer, so a refusal would have shown nothing. It now shows the server's sentence.

### M-1c-08 Another seller cannot see or change the offer

**Who:** owner.mobile@myplus.com (Mobile Distributor), developer tools  
**Before:** Shahzad Mobile Shop's offer id is known.  
**Covers:** MKT-R22.1  
**Evidence:** recorded 2026-10-03 22:34 UTC  
**Automated by:** MKT-1c-08

| # | Do this | Expect |
|---|---|---|
| 1 | As owner.mobile@myplus.com: GET /mkt/myOffers. | Works (positive control): Mobile Distributor's own offers. |
| 2 | GET /mkt/getOffer?id=<Shahzad's offer>, then POST /mkt/saveOffer {id: <Shahzad's offer>, marketplacePrice: 1}. | Both answer "No such offer." — exactly what an id that does not exist gets. |
| 3 | POST /platform/mkt/decideOffer {id: <Shahzad's offer>, decision: "SUSPEND", note: "x"}. | Refused: a seller is not the operator. Shahzad's offer is unchanged (still Live at 52,000). |
| C1 | Nothing changed. | Nothing to undo. |


## MKT-1d

### M-1d-01 One product, two sellers, from the lowest price

**Who:** Customer (incognito window)  
**Before:** Shahzad Mobile Shop: Samsung Galaxy A32 128GB Black at Rs 52,000, delivery in 4 hours, 12-month warranty (Samsung Pakistan), 7-day returns. Mobile Distributor: the SAME product at Rs 51,500, 24 hours, 6-month warranty. Both Live, both deliver to Karachi only (the MKT-1c steps, done for each).  
**Covers:** MKT-R5.4, MKT-R7.2, MKT-R18.2  
**Evidence:** recorded 2026-10-03 22:34 UTC  
**Automated by:** MKT-1d-01, MKT-1d-06

| # | Do this | Expect |
|---|---|---|
| 1 | Open /marketplace in an incognito window. City "Karachi". Search "Galaxy A32 T179106665693787d". | One card: "Samsung Galaxy A32 T179106665693787d 128GB Black", "From Rs. 51,500", "Available from 2 sellers", "Delivery in 4 hours" (the fastest seller's promise). |
| 2 | Open the card. | Two sellers. Shahzad Mobile Shop: Rs. 52,000, Delivery in 4 hours, 12 months warranty · Samsung Pakistan, Returns within 7 days, "No ratings yet", "Stock checked … ago". Mobile Distributor: Rs. 51,500, Delivery in 1 day, 6 months. |
| 3 | Press the browser's Back button. | The same search results come back. |
| C1 | None: the next MKT-1d cases use these two offers. | — |

### M-1d-02 The customer chooses the order

**Who:** Customer (incognito window)  
**Before:** As M-1d-01.  
**Covers:** MKT-R7.1, MKT-R18.4  
**Evidence:** recorded 2026-10-03 22:34 UTC  
**Automated by:** MKT-1d-02, MKT-1d-05

| # | Do this | Expect |
|---|---|---|
| 1 | On the product page open "Sort sellers by". | Five choices: Recommended, Lowest price, Fastest delivery, Longest warranty, Longest returns. Nearest, rating and promotion are NOT offered: there is no data behind them yet. |
| 2 | Choose "Lowest price". | Mobile Distributor (Rs. 51,500) is first. |
| 3 | Choose "Fastest delivery". | Shahzad Mobile Shop (4 hours) is first. |
| 4 | Choose "Longest warranty". | Shahzad Mobile Shop (12 months) is first. |
| 5 | Choose "Lowest price" again and reload the page. | Still "Lowest price" with Mobile Distributor first: the choice lives in the address (…&sort=LOWEST_PRICE). |
| 6 | Copy the address into another browser (here: cookies cleared) and open it. | The same order: "Lowest price", Mobile Distributor first. |
| 7 | Change the address to …&sort=<script> and open it. | The page opens on "Recommended" with both sellers. No error page. |
| C1 | Nothing was changed. | Nothing to undo. |

### M-1d-03 Nothing is chosen for the customer

**Who:** Customer (incognito window), keyboard  
**Before:** As M-1d-01, sorted by Lowest price.  
**Covers:** MKT-R7.3, MKT-R7.1  
**Evidence:** recorded 2026-10-03 22:34 UTC  
**Automated by:** MKT-1d-03

| # | Do this | Expect |
|---|---|---|
| 1 | Open the product sorted by Lowest price. Look at the button at the bottom before choosing. | No seller is selected. The button reads "Choose a seller first" and is disabled. |
| 2 | Using the keyboard (Tab to the list, then Space), choose Shahzad Mobile Shop — the dearer one. | Shahzad Mobile Shop's row is selected; the button reads "Buy from Shahzad Mobile Shop". |
| 3 | Press the button. | The checkout opens for Shahzad Mobile Shop: Total Rs. 52,000, and "Cash on delivery: you pay the seller when the order arrives. Nothing is charged now." |
| C1 | Close the window: nothing is placed until "Place order". | Nothing to undo. |

### M-1d-04 A city with no seller says so

**Who:** Customer (incognito window)  
**Before:** As M-1d-01: both offers serve Karachi only.  
**Covers:** MKT-R7.6, MKT-R20.1  
**Evidence:** recorded 2026-10-03 22:34 UTC  
**Automated by:** MKT-1d-04

| # | Do this | Expect |
|---|---|---|
| 1 | On the product page change the City box to "Lahore". | "No seller delivers this product to Lahore yet." No rows; the button stays disabled. |
| 2 | Go to the search, City "Lahore", search "Galaxy A32 T179106665693787d". | No card for the phone, and "No products match in Lahore. Try fewer words or another city." No error. |
| C1 | Nothing was changed. | Nothing to undo. |

> **Found by the walk:** DEFECT, fixed: a page still loading wrote the old city back into the box being typed in ("Lahore" became "KarachiLahore").

### M-1d-05 A paused offer leaves the card and the table together

**Who:** owner.mobile@myplus.com (Mobile Distributor) and a customer  
**Before:** As M-1d-01.  
**Covers:** MKT-R7.6, MKT-R5.4, MKT-R18.2, MKT-R18.5  
**Evidence:** recorded 2026-10-03 22:35 UTC  
**Automated by:** MKT-1d-07

| # | Do this | Expect |
|---|---|---|
| 1 | As owner.mobile@myplus.com: Sale → Marketplace → My offers → Pause on the Galaxy A32 offer. | The offer reads "Paused" and its button "Resume". |
| 2 | Customer: search "Galaxy A32 T179106665693787d" in Karachi and open the product. | The card reads "From Rs. 52,000 · Available from 1 seller"; the product page lists only Shahzad Mobile Shop. The card's number always equals the rows on the page. |
| C1 | As owner.mobile@: My offers → Resume. | Both sellers are back on the card and the page. |

### M-1d-06 The operator chooses the order customers see first

**Who:** admin@myplus.com (operator), then a customer and owner.business@  
**Before:** As M-1d-01.  
**Covers:** MKT-R7.4, MKT-R18.4  
**Evidence:** recorded 2026-10-03 22:35 UTC  
**Automated by:** MKT-1d-08

| # | Do this | Expect |
|---|---|---|
| 1 | As admin@myplus.com: Platform → Marketplace policies → "Order customers see first" → Lowest price → Save. | Saved. |
| 2 | Customer: open the product WITHOUT choosing a sort. | "Lowest price" is selected and Mobile Distributor is first. The customer can still change it. |
| 3 | Developer tools, as owner.business@ (a shop, not the operator): POST /platform/mkt/defaultSort {"sort":"FASTEST"}. | Refused. The setting stays "Lowest price". |
| C1 | As admin@: "Order customers see first" → Recommended → Save. | Saved; new visitors see Recommended again. |

### M-1d-07 Works in Urdu, on a phone, and keeps the page when the language changes

**Who:** Customer on a phone (375 px wide)  
**Before:** As M-1d-01.  
**Covers:** MKT-R7.6, MKT-R7.2  
**Evidence:** recorded 2026-10-03 22:35 UTC  
**Automated by:** i18n bundles (2865 keys × 6)

| # | Do this | Expect |
|---|---|---|
| 1 | On a phone, open the product page with ?lang=ur. | The page reads right to left (dir="rtl"), in Urdu. Nothing scrolls sideways. |
| 2 | Press "English" in the language links at the top. | The SAME product page opens in English (left to right), with the same two sellers — the language link keeps the page. |
| 3 | Run an accessibility check on the page (axe). | No serious or critical violations: every control has a name, contrast is sufficient. |
| C1 | Nothing was changed. | Nothing to undo. |

> **Found by the walk:** DEFECT, fixed: muted text on the public page was 4.43:1, under WCAG AA 4.5:1 (axe). Now 7.05:1.

### M-1d-08 Search text is just text

**Who:** Customer (incognito window)  
**Before:** As M-1d-01.  
**Covers:** MKT-R7.6  
**Evidence:** recorded 2026-10-03 22:35 UTC  
**Automated by:** MKT-1d-09, MKT-1d-10

| # | Do this | Expect |
|---|---|---|
| 1 | Search for: % | Answers normally ("No products match in Karachi…"); it does NOT list every product. |
| 2 | Search for: _ | Answers normally ("No products match in Karachi…"); it does NOT list every product. |
| 3 | Search for: ' OR 1=1 -- | Answers normally ("No products match in Karachi…"); it does NOT list every product. |
| 4 | Search for: %{enter} | Answers normally ("No products match in Karachi…"); it does NOT list every product. |
| 5 | Open /marketplace?product=999999999 | "No such product." No error page. |
| C1 | Nothing was changed. | Nothing to undo. |

> **Found by the walk:** DEFECT, fixed: a search containing braces ("%{enter}") was read as a URL template: a raw 500 and "InternalError" on the page. Shopper text is now strictly encoded, and a server fault shows a sentence (MarketplacePublicControllerTest).


## MKT-1e

### M-1e-01 Buying waits for the seller, never pretends; the sale lands in the seller's books

**Who:** Customer (incognito window), then owner.business@myplus.com  
**Before:** As M-1d-01 (Shahzad Mobile Shop Live at Rs 52,000 in Karachi). The acceptance window is 5 minutes.  
**Covers:** MKT-R10.2, MKT-R18.5, MKT-R10.1, MKT-R1.3  
**Evidence:** recorded 2026-10-03 22:35 UTC  
**Automated by:** MKT-1e-01

| # | Do this | Expect |
|---|---|---|
| 1 | Customer: open the product (Karachi), choose Shahzad Mobile Shop, press "Buy from Shahzad Mobile Shop". Name "Ali", phone 03006937871, address "1 Clifton". Check the total. | Total Rs. 52,000. The payment line reads "Cash on delivery: you pay the seller when the order arrives. Nothing is charged now." |
| 2 | Press "Place order". | "Waiting for Shahzad Mobile Shop to confirm", an order number MKT-…, and "Shahzad Mobile Shop has 4:5x to confirm. Your stock is held." The address bar shows ?order=MKT-… and NO phone number. |
| 3 | As owner.business@myplus.com: Sale → Marketplace → "Incoming marketplace orders" ("Waiting for you"). | The order is listed with the items, Ali, the phone and address, and a countdown under 5:00 running down. |
| 4 | Press Accept. | The row turns to "Accepted" with "Invoice INV-…", "In your orders as …" and "Accepted. The sale is in your books; deliver and collect the cash." |
| 5 | Customer: reopen the order page (the same address, with the phone number) and wait up to 10 seconds. | "Confirmed by Shahzad Mobile Shop" and "Shahzad Mobile Shop will deliver and collect Rs. 52,000 in cash." |
| C1 | None: an accepted sale is a real sale in the seller's books (return it with the store's Sale Returns if needed). | — |

> **Found by the walk:** DEFECT, fixed (gate): after Accept the order vanished from "Waiting for you", so the seller never saw it go through. The row now turns to Accepted in place, with the server's sentence.

### M-1e-02 One checkout is one seller

**Who:** Customer (incognito window), developer tools  
**Before:** As M-1d-01.  
**Covers:** MKT-R20.1, MKT-R17.1, MKT-R20.2  
**Evidence:** recorded 2026-10-03 22:35 UTC  
**Automated by:** MKT-1e-09

| # | Do this | Expect |
|---|---|---|
| 1 | On the product page choose Shahzad Mobile Shop, then Mobile Distributor. | Only one can be chosen (radio buttons): choosing Mobile Distributor unchooses Shahzad Mobile Shop; the button reads "Buy from Mobile Distributor". |
| 2 | Developer tools: POST /marketplace/public/checkout for Shahzad Mobile Shop's offer, adding "lines": [{offerId: <Mobile Distributor's offer>}]. | The extra "lines" are ignored: the order has exactly one seller order, for Shahzad Mobile Shop. Mixing sellers in one checkout waits for Phase 2. |
| C1 | As owner.business@: Incoming → Reject that order with the reason "walk cleanup". | The stock is released; the customer's page reads Cancelled. |

### M-1e-03 The seller rejects: the stock comes back and the customer is told

**Who:** Two customers (two incognito windows), then owner.business@  
**Before:** Shahzad Mobile Shop has a second Live offer with exactly 2 in stock (another phone, its own product).  
**Covers:** MKT-R10.2, MKT-R10.5, MKT-R10.3  
**Evidence:** recorded 2026-10-03 22:36 UTC  
**Automated by:** MKT-1e-04

| # | Do this | Expect |
|---|---|---|
| 1 | Customer 1 (phone 03006937873): open that product, choose Shahzad Mobile Shop, Quantity 2, Place order. | "Waiting for Shahzad Mobile Shop to confirm". Both units are now held. |
| 2 | Customer 2 (another window, phone 03006937874): the same product, Quantity 1, Place order. | Refused on the checkout: "This seller no longer has enough stock. Please choose another offer." Nothing is placed. |
| 3 | As owner.business@: Incoming → the order for 2 → leave the reason box EMPTY → Reject. | Refused under the buttons: "Give a reason. MaxTheService support will see it." The order still waits. |
| 4 | Type "out of stock in store" in the reason box and press Reject. | The row turns to "Rejected" with the reason. |
| 5 | Customer 1: reopen the order page. | "Cancelled" and "The seller could not fulfil this order." |
| 6 | Customer 2: Place order again. | "Waiting for Shahzad Mobile Shop to confirm": the stock came back. |
| C1 | As owner.business@: Incoming → Reject customer 2's order with the reason "walk cleanup". | Rejected; its unit is released. |

### M-1e-04 Nobody answers: the hold expires, and a late Accept is refused

**Who:** admin@myplus.com, a customer, owner.business@  
**Before:** As M-1d-01.  
**Covers:** MKT-R10.2, MKT-R19.1, MKT-R18.5, MKT-R10.5, MKT-R7.4  
**Evidence:** recorded 2026-10-03 22:38 UTC  
**Automated by:** MKT-1e-05

| # | Do this | Expect |
|---|---|---|
| 1 | As admin@myplus.com: Platform → Marketplace policies → "Minutes a seller has to accept an order" = 1 → Save. | Saved. |
| 2 | Customer (phone 03006937875): order Shahzad Mobile Shop's phone. The seller does nothing. | "Shahzad Mobile Shop has 0:5x to confirm. Your stock is held." |
| 3 | Wait about 2 minutes (the minute, a 30-second grace and one sweep). Reopen the customer's order page. | "Cancelled" and "The seller did not confirm in time." The stock is free again. |
| 4 | As owner.business@: Incoming → choose "All". | The order reads "Expired", with no Accept button. |
| 5 | Developer tools: replay the Accept the seller's page would have sent before the expiry (POST /mkt/acceptOrder with the old version). | Refused: "This order expired before it was accepted." — not "someone else changed it". |
| C1 | As admin@: "Minutes a seller has to accept an order" = 5 → Save. | Saved. |

> **Found by the walk:** DEFECT, fixed (gate): a late Accept on an expired order said "someone else changed this". The outcome is now checked before the version (MarketplaceOrderFlowTest).

### M-1e-05 A double click, or a lost answer, is still one order

**Who:** Customer (incognito window), developer tools (network)  
**Before:** As M-1d-01.  
**Covers:** MKT-R22.3  
**Evidence:** recorded 2026-10-03 22:38 UTC  
**Automated by:** MKT-1e-03, MarketplacePublicControllerTest

| # | Do this | Expect |
|---|---|---|
| 1 | Fill the checkout for Shahzad Mobile Shop (phone 03006937876) and DOUBLE-click "Place order". | One order number. The seller has exactly ONE order for that phone. |
| 2 | Another order (phone 03006937877): the server PLACES it, but the answer is lost on the way back (developer tools: the response replaced by "504 Gateway Timeout"). | The page does not guess: "We could not confirm your order. Press the button again; it will not be placed twice." |
| 3 | Press "Place order" again. | The order the server had already placed is shown — the SAME one: the seller has exactly ONE order for that phone. |
| C1 | As owner.business@: Reject both orders with the reason "walk cleanup". | Their stock is released. |

> **Found by the walk:** DEFECT, fixed: when the answer was lost (a 504 or a timeout) the page treated it as a "no", dropped its attempt key, and a second press placed a SECOND order. The proxy now says UNKNOWN and the page keeps the key (MarketplacePublicControllerTest).

### M-1e-06 The order keeps its own copy of the terms

**Who:** Customer and admin@myplus.com  
**Before:** An order placed on an offer whose policies are its own ("12 months · Samsung Pakistan", "7 days").  
**Covers:** MKT-R13.3, MKT-R3.1, MKT-R3.2  
**Evidence:** recorded 2026-10-03 22:38 UTC  
**Automated by:** MKT-1e-06

| # | Do this | Expect |
|---|---|---|
| 1 | Customer (phone 03006937878): order that phone. | The order page lists the line with "12 months warranty · Samsung Pakistan · Returns within 7 days" and Rs. 52,000. |
| 2 | As admin@myplus.com: Marketplace policies → Deactivate that "7 days" return policy. | It turns grey. |
| 3 | Customer: reopen the order page. | Unchanged: "… Returns within 7 days", Rs. 52,000 — the terms were copied onto the order when it was placed. |
| 4 | Developer tools: compare the customer's order (GET /marketplace/public/orders/…) with the seller's incoming order (GET /mkt/incomingOrders). | Only the SELLER sees the commission it was charged under (commissionBasis ITEMS); the customer's order has no commission field. |
| C1 | As owner.business@: Reject the order with "walk cleanup". | Released. |

### M-1e-08 Order, payment and the seller's answer are separate facts

**Who:** admin@myplus.com (operator)  
**Before:** Orders in each state exist (M-1e-01, -03, -04).  
**Covers:** MKT-R19.1, MKT-R20.1, MKT-R22.1  
**Evidence:** recorded 2026-10-03 22:39 UTC  
**Automated by:** MKT-1e-09

| # | Do this | Expect |
|---|---|---|
| 1 | As admin@myplus.com: Platform → "Marketplace orders" (opens on "Waiting for a seller"). | Only waiting orders, each with its number and total, the seller, the items and the city (here: Bilal's order to Karachi). |
| 2 | Choose "Confirmed". | M-1e-01's order is listed, by Shahzad Mobile Shop. |
| 3 | Choose "Cancelled". | M-1e-03's order shows "The seller could not fulfil this order."; M-1e-04's shows "The seller did not confirm in time." |
| 4 | Developer tools: GET /platform/mkt/orders?status=CONFIRMED, read M-1e-01's order. | status CONFIRMED, paymentMode COD, paymentStatus UNPAID: cash is collected at delivery; settlement is MKT-1g. |
| 5 | As owner.business@ (a shop): GET /platform/mkt/orders. | Refused: a shop never sees the platform's order list. |
| C1 | As owner.business@: Reject Bilal's order with "walk cleanup". | Released. |

### M-1e-09 Phase 1 is cash on delivery; a seller who takes no cash is not offered

**Who:** Customer, and owner.business@  
**Before:** As M-1d-01.  
**Covers:** MKT-R20.1  
**Evidence:** recorded 2026-10-03 22:39 UTC  
**Automated by:** MarketplaceOrderFlowTest.codOffRefused

| # | Do this | Expect |
|---|---|---|
| 1 | Customer: open the checkout for any seller. | "Cash on delivery: you pay the seller when the order arrives. Nothing is charged now." No card or wallet choice. |
| 2 | The seller switches cash on delivery OFF (store delivery setting "Accept cash on delivery", key order.payment.codEnabled), then a customer tries to order from them. *(screen not available: SCREEN NOT AVAILABLE: the setting lives in marketplace-service and has no screen in the business dashboard yet (Configuration lists business-service and auth settings only). Written from MarketplaceCheckoutService.checkout (line 137).)* | Refused at "Place order": "This seller does not accept cash on delivery yet. Please choose another offer." Nothing is held. |
| C1 | Nothing was changed. | Nothing to undo. |

> **Found by the walk:** GAP, open: a seller cannot switch cash on delivery off from any screen (the setting lives in marketplace-service; Configuration shows business and auth settings only). The refusal had no test: MarketplaceOrderFlowTest.codOffRefused added.

### M-1e-10 Phones need their IMEI; nothing is let go before it is given

**Who:** owner.business@myplus.com  
**Before:** Shahzad Mobile Shop has a Live phone offer whose product is set to "requires serial number" (Products → edit → "Track serial numbers"), and RECEIVED two of those phones with their IMEIs on a purchase (Purchase → New purchase, IMEIs typed in). A customer has ordered 2.  
**Covers:** MKT-R10.2, MKT-R10.3, MKT-R10.4, MKT-R10.1  
**Evidence:** recorded 2026-10-03 22:39 UTC  
**Automated by:** MarketplaceOrderFlowTest (serials)

| # | Do this | Expect |
|---|---|---|
| 1 | As owner.business@: Incoming → the order for 2 → press Accept without typing any IMEI. | Refused under the buttons: "Enter the serial number (IMEI) of each unit you are sending: 2 for …". The order still waits; the stock stays held. |
| 2 | Type ONE IMEI (351066656937871) and press Accept. | The same refusal: two units need two IMEIs. |
| 3 | Type the second IMEI (351066656937872) and press Accept. | Accepted: "Invoice INV-…"; the invoice records those two IMEIs as sold. (An IMEI the shop never received is refused: "The sale could not be recorded: Serial … is not in stock." — and the order keeps waiting, stock held.) |
| C1 | None: the sale is real. Undo it through the store's Sale Returns if needed. | — |

> **Found by the walk:** Case corrected: an IMEI must have been RECEIVED on a purchase to be sold; an unknown one is refused ("Serial … is not in stock") and the order keeps waiting.

### M-1e-11 No one can hold a shop's stock hostage; an expired page says so

**Who:** Customer (incognito window)  
**Before:** As M-1d-01.  
**Covers:** MKT-R22.3, MKT-R22.1  
**Evidence:** recorded 2026-10-03 22:39 UTC  
**Automated by:** MKT-1e-10, MKT-1e-08

| # | Do this | Expect |
|---|---|---|
| 1 | With phone 03006937879, place 3 orders and leave them unanswered. | Each reads "Waiting for … to confirm". |
| 2 | Place a 4th with the SAME number written differently: (0300) 6937879. | Refused: "You already have 3 orders waiting for sellers to confirm. Please wait for an answer first." |
| 3 | Place one with a different number (03006937870). | Placed: the limit is per number, not per shop. |
| 4 | Open the checkout, then let the page's security token lapse (developer tools: delete the XSRF-TOKEN cookie) and press "Place order". | "This page expired. Please reload it and try again." Nothing is placed. |
| C1 | As owner.business@: Reject the 4 waiting orders with "walk cleanup". | Their stock is released. |

> **Found by the walk:** DEFECT, fixed: when the page's security token lapsed, Spring redirected to the login page and the shopper saw "Save failed" — the "This page expired" sentence was never reachable. The page now reads the redirect as an expired page.


## MKT-1e2

### M-1e2-01 A shopper creates an account with phone and password

**Who:** Customer (incognito window)  
**Before:** Shahzad Mobile Shop has a Live phone offer in Karachi (as M-1d-01).  
**Covers:** MKT-R1.1, MKT-R22.3  
**Evidence:** recorded 2026-10-03 22:39 UTC  
**Automated by:** MKT-1e2-01, MKT-1e2-02

| # | Do this | Expect |
|---|---|---|
| 1 | Open /marketplace in an incognito window and press "Sign in" at the top. | The account panel opens: Phone number, Password, "Sign in", and "New here? Create an account". The forgot-password line says to contact MaxTheService support with an order number and the phone you ordered with. |
| 2 | Press "New here? Create an account". Phone 03126937871, Your name "Ali Raza", Password "short". Press "Create account". | Refused: "Choose a password of at least 8 characters." The hint under the password says "At least 8 characters. Not your phone number." |
| 3 | Password "Shop!ng2026". Press "Create account". | Signed in: the top button now reads "Ali Raza"; "My orders" shows "No orders yet." with "Add an order you placed before" below. |
| 4 | Developer tools → Application → Cookies: look for MKT_SESSION. | The session cookie is HttpOnly (page scripts cannot read it) and SameSite=Lax; document.cookie does not contain it. |
| 5 | Press "Sign out", then try to create another account with the same phone written as (0312) 6937871. | Refused: "This phone number already has an account. Sign in instead." — one account per phone, however it is written. |
| C1 | Nothing to undo: the account stays for the next cases. | — |

### M-1e2-02 Five wrong passwords lock the phone for 15 minutes

**Who:** Customer  
**Before:** An account exists for a phone (here a fresh one).  
**Covers:** MKT-R22.3  
**Evidence:** recorded 2026-10-03 22:39 UTC  
**Automated by:** MKT-1e2-03

| # | Do this | Expect |
|---|---|---|
| 1 | Sign in with phone 03126937872 and a wrong password, five times. | Each time: "The phone number or password is not right." — the same sentence an unknown phone gets. |
| 2 | Now type the RIGHT password and press "Sign in". | Refused: "Too many wrong passwords. Please try again in 15 minutes." |
| C1 | Wait 15 minutes; the lock lifts by itself. | Signing in works again after 15 minutes. |

### M-1e2-03 My orders shows only what is proven yours; an earlier order is added by number + phone

**Who:** Customer  
**Before:** M-1e2-01 done (account for the walk phone).  
**Covers:** MKT-R1.1, MKT-R22.1  
**Evidence:** recorded 2026-10-03 22:39 UTC  
**Automated by:** MKT-1e2-04, MKT-1e2-05

| # | Do this | Expect |
|---|---|---|
| 1 | Signed OUT, order the phone with phone 03126937871 (cash on delivery). | "Waiting for Shahzad Mobile Shop to confirm" — an anonymous order. |
| 2 | Sign in as 03126937871 and open My orders. | The anonymous order is NOT there: the same phone is not proof that it is yours. |
| 3 | Under "Add an order you placed before": the order number and a WRONG phone (03009999999). Press "Add to my orders". | Refused: "No such order. Check the order number and the phone it was placed with." |
| 4 | The same number with phone 03126937871. Press "Add to my orders". | The order appears in My orders with its seller, "Waiting for the seller", the total and "Cash on delivery". |
| 5 | Signed in, order again (cash on delivery). | The new order is in My orders at once — no claim needed. |
| C1 | None: M-1e2-04 cancels these orders. | — |

> **Found by the walk:** DEFECT, fixed (design trace): a lost card charge could never be refunded — the payment fact is now written before the provider is called and reconciled by its idempotency key (MarketplacePaymentServiceTest).

### M-1e2-04 The shopper cancels while the seller has not answered; after Accept they cannot

**Who:** Customer, then owner.business@myplus.com  
**Before:** M-1e2-03 done: two waiting orders in My orders.  
**Covers:** MKT-R10.5  
**Evidence:** recorded 2026-10-03 22:40 UTC  
**Automated by:** MKT-1e2-06

| # | Do this | Expect |
|---|---|---|
| 1 | Signed in as 03126937871: My orders → "Cancel order" on the first order → reason "changed my mind" → "Cancel order". | The order turns "Cancelled" with "You cancelled this order. changed my mind"; its Cancel button is gone. |
| 2 | As owner.business@myplus.com: Sale → Marketplace → Incoming → "All". | The order reads "Cancelled": the seller is told, and the held stock was given back. |
| 3 | The seller presses Accept on the OTHER waiting order. Then the shopper opens My orders. | That order reads "Confirmed" and has no Cancel button: after Accept, cancelling is a support case. |
| C1 | None: the accepted sale is real (return it through Sale Returns if needed). | — |

### M-1e2-05 Pay online now: charged at once; a seller reject refunds it exactly once

**Who:** Customer, then owner.business@myplus.com  
**Before:** Signed in (M-1e2-01).  
**Covers:** MKT-R19.1, MKT-R20.1, MKT-R13.1  
**Evidence:** recorded 2026-10-03 22:40 UTC  
**Automated by:** MKT-1e2-07

| # | Do this | Expect |
|---|---|---|
| 1 | Signed in, buy the phone. At "How do you want to pay?" choose "Pay online now". | A card field appears, labelled test mode: "Test payments only: no real card is charged." The cash-on-delivery line is hidden. |
| 2 | Card "4242 4242 4242 4242", your details, "Place order". | "Waiting for Shahzad Mobile Shop to confirm"; in My orders the order reads "Paid online". |
| 3 | As owner.business@: Incoming → that order → reason "out of stock" → Reject. Then the shopper reopens My orders. | The order reads "Cancelled", "The seller could not fulfil this order." and "Refunded". |
| 4 | Developer tools: GET /marketplace/account/orders and read that order's payments. | Exactly ONE succeeded CHARGE and ONE succeeded REFUND for the same amount — never two. |
| C1 | None: a refunded order is complete. | — |

> **Found by the walk:** DEFECT, fixed (design trace): a card order reached the seller's books as cash on delivery, so a rider could collect cash for an order already paid. It now records MARKETPLACE. Every cancelling path refunds exactly once.

### M-1e2-06 A declined card places nothing

**Who:** Customer  
**Before:** Signed in (M-1e2-01).  
**Covers:** MKT-R19.1  
**Evidence:** recorded 2026-10-03 22:40 UTC  
**Automated by:** MKT-1e2-08

| # | Do this | Expect |
|---|---|---|
| 1 | Signed in, buy the phone, "Pay online now", card "fail" (the test card that is always declined). "Place order". | Refused on the checkout: "Your card was declined. Please use another card or choose cash on delivery." No order number is shown. |
| 2 | As owner.business@: Incoming → "Waiting for you". | No new order from this shopper is waiting: the seller never sees a declined order, and its stock was given back. |
| C1 | Nothing was placed. | Nothing to undo. |

### M-1e-07 One customer account across sellers

**Who:** Customer "Ali", phone 0300-1234567 (incognito window)  
**Before:** Ali has placed one order with Shahzad Mobile Shop and one with Mobile Distributor using the same phone.  
**Covers:** MKT-R8.1  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Open /marketplace → "Sign in" → the phone number and password (owner ruling: phone + password; no one-time code until an SMS provider is chosen). | Signed in; the top button shows Ali's name. |
| 2 | Look at the list. | Both orders, each naming its seller, newest first. "Support: MaxTheService" on both, never a seller's phone number. |
| C1 | Sign out. | The order list is no longer shown. |


## MKT-1f

### M-1f-01 One place to complain

**Who:** Customer → MaxTheService operator → owner.business@myplus.com  
**Before:** Ali's order from Shahzad Mobile Shop is DELIVERED (the seller recorded the delivery).  
**Covers:** MKT-R8.2, MKT-R22.1  
**Evidence:** recorded 2026-10-03 22:41 UTC  
**Automated by:** MKT-1f-01, MKT-1f-02, MKT-1f-03

| # | Do this | Expect |
|---|---|---|
| 1 | Customer: open /marketplace → your name → My orders. | The order reads "Delivered" and shows "Get help". |
| 2 | "Get help" → "Something is wrong with my order" → note "Box was open" → "Send to MaxTheService". | A help request SC-… appears under the order; nowhere is a seller phone number shown. |
| 3 | Operator: platform dashboard → Support cases → the case → write "check seller history", tick "Internal note" → Send. Then write "Check the box and call the customer" → "Task the seller". | The thread shows the internal note marked (internal); the case reads "Waiting for seller". |
| 4 | Seller A: Sale → Marketplace → "Tasks from MaxTheService" → the task → answer "Charger sent with our rider today" → Send. | The task shows the order number, the customer's pickup details and the operator's task — not the internal note. The answer is sent. |
| 5 | Seller B (owner.mobile@) opens the same Tasks box. | Seller A's case is not there. |
| 6 | Customer: My orders. | The seller's answer is shown signed "MaxTheService support"; the internal note is NOT shown. |
| C1 | Operator: the case → write "walk cleanup" → Resolve. | The case reads Resolved. |

> **Found by the walk:** DEFECT, fixed (trace): a shopper could request a return straight on the seller's store order (sequential id + phone), bypassing MaxTheService. Refused for marketplace orders (M-1f-07).

### M-1f-02 Return cost follows the cause

**Who:** Customer, then MaxTheService operator  
**Before:** Delivered test orders (M-1f-01's offer, 7 return days).  
**Covers:** MKT-R13.1, MKT-R13.3  
**Evidence:** recorded 2026-10-03 22:41 UTC  
**Automated by:** MKT-1f-04, MKT-1f-05, MKT-1f-06

| # | Do this | Expect |
|---|---|---|
| 1 | Customer: My orders → Get help → "Return this item" → "Wrong item sent" → Send. | "Return requested" RT-… under the order; the refund shown is Rs 52,000. |
| 2 | On the second order: Return this item → "Changed my mind" → Send. | Return requested; the refund is Rs 51,750 with "pickup fee Rs 250" — the customer bears a change of mind. |
| 3 | Operator: Support cases → the first case. | Cost bearer: FULFILLER (Shahzad Mobile Shop) — resolved from what the order line recorded when it was placed. |
| 4 | The second case. | Cost bearer: CUSTOMER; Refund 51750.00 (−250.00). |
| C1 | Operator: each return → note "walk cleanup" → Reject. | They read REJECTED; the customer is told why. |

### M-1f-03 A paid-online return end to end

**Who:** Customer → MaxTheService operator → owner.business@myplus.com  
**Before:** A delivered Rs 52,000 order paid online, inside its 7-day return window.  
**Covers:** MKT-R13.1, MKT-R13.2  
**Evidence:** recorded 2026-10-03 22:41 UTC  
**Automated by:** MKT-1f-04

| # | Do this | Expect |
|---|---|---|
| 1 | Customer: My orders → Get help → Return this item → "Wrong item sent" → Send. | "Return requested" RT-…; the order reads "Paid online". |
| 2 | Operator: Support cases → the case → note "pickup tomorrow" → Approve. | "Approved. The seller's rider collects it." The return reads APPROVED. |
| 3 | Seller A: Tasks from MaxTheService → the return → "Restock: back on the shelf" → "Item received". (No "Cash handed back" box: it was paid online.) | "Received. The customer's refund is done." The task leaves the list. |
| 4 | Developer tools: GET /getOrder for the store order behind it. | fulfilmentStatus RETURNED — the seller's books took a credit note against the invoice. |
| 5 | Customer: My orders. | The help request shows "Refunded" and the message that Rs 52,000 is on its way to the card; the order shows one refund. |
| C1 | None: a refunded return is complete. (Pressing "Item received" again changes nothing: one credit note, one refund.) | — |

> **Found by the walk:** DEFECT, fixed (trace): the seller's own "Process return" reversed the sale in their books while the customer's online payment was never refunded. A marketplace return now runs through MaxTheService: a credit note on the invoice, then the refund, once.

### M-1f-04 Unsafe or expired goods escalate at once

**Who:** Customer → MaxTheService operator  
**Before:** A delivered order.  
**Covers:** MKT-R13.4  
**Evidence:** recorded 2026-10-03 22:43 UTC  
**Automated by:** MKT-1f-08

| # | Do this | Expect |
|---|---|---|
| 1 | Customer: My orders → Get help → Return this item → "Expired or unsafe" → note "battery swollen" → Send. | Return requested. |
| 2 | Operator: Support cases. | The case is marked URGENT and sits in the urgent group at the top — above every case that is not urgent, however much older. |
| C1 | Operator: the return → note "walk cleanup" → Reject; then resolve the case with "walk cleanup". | Rejected; Resolved. |

> **Found by the walk:** DEFECT, fixed (walk): My orders drew itself twice on load, and a quick "Get help" click lost its form to the second draw. Loads now run one at a time.

### M-1f-05 Every action leaves a trail

**Who:** owner.business@myplus.com  
**Before:** After M-1f-01 to -04.  
**Covers:** MKT-R22.4  
**Evidence:** recorded 2026-10-03 22:41 UTC  
**Automated by:** MKT-1f-12

| # | Do this | Expect |
|---|---|---|
| 1 | Developer tools, signed in as Seller A: read the business's audit trail. | MKT_CASE_TASKED and MKT_RETURN_DECIDED rows for Seller A's orders, actor type PLATFORM_OPERATOR — the operator's actions, filed in the seller's own trail. |
| C1 | Nothing to undo: the trail is append-only. | — |

> **Found by the walk:** GAP, closed (trace, G-16): marketplace-service wrote no audit row for any marketplace action. It now uses the shared audit outbox; support and return actions are filed in the seller's trail.

### M-1f-06 A cash-on-delivery return: cash back at pickup

**Who:** Customer → MaxTheService operator → owner.business@myplus.com  
**Before:** A delivered cash-on-delivery order.  
**Covers:** MKT-R13.2  
**Evidence:** recorded 2026-10-03 22:42 UTC  
**Automated by:** MKT-1f-10

| # | Do this | Expect |
|---|---|---|
| 1 | Customer: Return this item → "Does not work" → Send. Operator: approve it. | Approved; the customer is told the rider hands Rs 52,000 back in cash when collecting it. |
| 2 | Seller A: Tasks → the return → "Quarantine" → "Item received" WITHOUT ticking "Cash handed back". | Refused: "This order was paid in cash: hand Rs 52,000 back to the customer at pickup, then tick "Cash handed back"." (ruling R-MKT-12) |
| 3 | Tick "Cash handed back (Rs 52,000)" → "Item received". | "Received. The customer's refund is done." — recorded as cash at pickup; no card refund is attempted; the unit is quarantined, not sellable. |
| C1 | None: the return is complete. | — |

### M-1f-07 No way around MaxTheService

**Who:** Customer, then owner.business@myplus.com  
**Before:** A delivered marketplace order (store order SO-…).  
**Covers:** MKT-R8.2  
**Evidence:** recorded 2026-10-03 22:42 UTC  
**Automated by:** MKT-1f-09

| # | Do this | Expect |
|---|---|---|
| 1 | As the shopper, ask the shop's own return path (POST /storefront/return) with the store order and the phone. | Refused: "Returns for marketplace orders go through MaxTheService: open My orders on the marketplace and choose Get help." |
| 2 | Seller A: the store order → Process return (POST /processReturn). | Refused: "This is a marketplace order: its return goes through MaxTheService, which refunds the customer." The order stays Delivered. |
| C1 | Nothing was changed. | — |


## MKT-1g

### M-1g-01 The worked example adds up

**Who:** owner.business@myplus.com (Shahzad Mobile Shop)  
**Before:** A delivered Rs 5,000 order: fixed commission Rs 500, delivery Rs 200, processing Rs 50, reserve Rs 100.  
**Covers:** MKT-R15.5, MKT-R16.1  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Sale → Marketplace → Settlement statement → open the line. | Customer paid 5,000.00 · Commission 500.00 · Delivery 200.00 · Processing 50.00 · Reserve 100.00 · Payable 4,150.00. |
| 2 | Add them up. | 4,150 + 500 + 200 + 50 + 100 = 5,000. |
| C1 | Nothing to undo. | — |

### M-1g-02 Nothing is payable before delivery and the return window

**Who:** owner.business@myplus.com (Shahzad Mobile Shop)  
**Before:** An order placed today, not delivered.  
**Covers:** MKT-R15.2, MKT-R15.3, MKT-R16.2  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Settlement statement. | The line is NOT_ELIGIBLE. |
| 2 | Record delivery; reload. | PENDING_RETURN_WINDOW. |
| 3 | After the return window ends; reload. | ELIGIBLE. |
| C1 | Nothing to undo. | — |

### M-1g-03 T+1 skips the weekend

**Who:** owner.business@myplus.com (Shahzad Mobile Shop)  
**Before:** Delivery recorded on a Friday; trigger DELIVERED, T+1.  
**Covers:** MKT-R15.1  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Settlement statement → the line's "Payable on". | The following Monday (Tuesday if Monday is a bank holiday). |
| C1 | Nothing to undo. | — |

### M-1g-04 Payouts need two people and happen once

**Who:** Two operator accounts  
**Before:** Seller A has an ELIGIBLE balance.  
**Covers:** MKT-R16.3, MKT-R22.3  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Operator 1: Payouts → Request for Seller A. | Payout PO-… REQUESTED. |
| 2 | Operator 1: Approve it. | Refused: "Another person must approve this payout." |
| 3 | Operator 2: Approve. | APPROVED. |
| 4 | Operator 1: Mark paid, bank reference TRX-123. | PAID with TRX-123. |
| 5 | Refresh and press Request again. | The same payout is shown; no second one is created. |
| C1 | None: payouts are records. Use a test seller. | — |

### M-1g-05 Mistakes are reversed, not edited

**Who:** admin@myplus.com (operator)  
**Before:** A PAID ledger line.  
**Covers:** MKT-R15.6, MKT-R16.2  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Look for an Edit action on any ledger line. | There is none. |
| 2 | Record a correction of −100. | A new ADJUSTMENT line −100.00; the original line is unchanged. |
| C1 | Record +100 to cancel the test correction. | The balance is back. |

### M-1g-06 Commission reaches the books

**Who:** admin@myplus.com (operator)  
**Before:** Note the trial balance line "Marketplace commission".  
**Covers:** MKT-R15.5, MKT-R1.3  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Settle one delivered order with commission Rs 480. | Settled. |
| 2 | Finance → Trial balance. | "Marketplace commission" is higher by exactly 480.00; the seller-payable control account moved by the payable. |
| C1 | Nothing to undo: the journal is the record. | — |


## MKT-2

### M-2-01 A two-seller basket splits into seller orders

**Who:** Customer "Ali", phone 0300-1234567 (incognito window)  
**Before:** Phase 2 switched on.  
**Covers:** MKT-R17.2, MKT-R20.3  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Add Seller A's and Seller B's offers to one basket → Checkout → Place order. | One order number with two parts, each naming its seller, promise and delivery fee. |
| 2 | Seller B rejects its part. | Only that part is cancelled; Seller A's part continues. |
| C1 | Seller A rejects its part with "walk cleanup". | Both parts are cancelled; stock released. |

### M-2-02 Shortage moves the order only on the same or better terms

**Who:** owner.business@myplus.com (Shahzad Mobile Shop) and Customer "Ali", phone 0300-1234567 (incognito window)  
**Before:** Phase 2. Seller B has the same phone cheaper and faster.  
**Covers:** MKT-R11.1, MKT-R11.2  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Seller A rejects for "shortage". | The order moves to Seller B and the customer's page reads "Now from Seller B". |
| 2 | Repeat with Seller B dearer or slower. | The customer is ASKED first; nothing moves until they answer. |
| C1 | Reject the test orders. | Released. |

### M-2-03 Never a different variant without asking

**Who:** Customer "Ali", phone 0300-1234567 (incognito window)  
**Before:** Phase 2. Only a 64GB is in stock.  
**Covers:** MKT-R11.3  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | The seller rejects the 128GB line for shortage. | The customer is asked "Accept 64GB instead?"; nothing changes until they answer. |
| 2 | Decline. | The line is cancelled and refunded. |
| C1 | Nothing to undo. | — |

### M-2-04 Shortage cause and responsibility are recorded

**Who:** admin@myplus.com (operator)  
**Before:** After M-2-02.  
**Covers:** MKT-R11.4, MKT-R12.4  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Operator → the order → Shortage panel. | Cause "merchant stock not updated", responsible party Seller A, effect on seller performance. No money taken without the dispute step. |
| C1 | Nothing to undo. | — |

### M-2-05 Checkout never hangs on a slow seller

**Who:** Customer "Ali", phone 0300-1234567 (incognito window)  
**Before:** Phase 2. The operator's test switch makes one seller slow.  
**Covers:** MKT-R18.1, MKT-R18.3, MKT-R18.5  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Check out an item whose top offer belongs to the slow seller. | Within about 2 seconds the next seller is tried, or the screen says "We are checking availability". It never spins without end. |
| C1 | Operator: switch the test slowness off. | Normal. |

### M-2-06 Acceptance terms differ by order value

**Who:** admin@myplus.com (operator)  
**Before:** Phase 2. Rule: orders above Rs 100,000 get 15 minutes.  
**Covers:** MKT-R10.6  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Place a Rs 150,000 order. | The seller's countdown starts at 15:00. |
| C1 | Reject it with "walk cleanup". | Released. |


## MKT-3

### M-3-01 Platform stock from the MaxTheService warehouse

**Who:** admin@myplus.com (operator) and Customer "Ali", phone 0300-1234567 (incognito window)  
**Before:** Phase 3.  
**Covers:** MKT-R4.2, MKT-R20.4, MKT-R1.1, MKT-R1.2  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Warehouse: receive 100 education kits. | Stock 100. |
| 2 | Customer buys one. | The offer reads "Sold and shipped by MaxTheService"; stock 99; picking and packing happen in the warehouse screens. |
| C1 | Return the kit. | Stock 100. |


## MKT-4

### M-4-01 Supplier stock is never "in stock today" without a promise

**Who:** Customer "Ali", phone 0300-1234567 (incognito window)  
**Before:** Phase 4. A distributor offer with a 20-minute acceptance window.  
**Covers:** MKT-R4.3, MKT-R4.4, MKT-R20.5  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Open the product. | The supplier offer shows "Ships from supplier · confirmation within 20 minutes", never "In stock, today". |
| C1 | Nothing to undo. | — |

### M-4-02 A slow supplier API does not hang checkout

**Who:** admin@myplus.com (operator)  
**Before:** Phase 4. The supplier endpoint is delayed 5 s.  
**Covers:** MKT-R22.5  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Check out the supplier offer. | An answer within the routing deadline; the supplier call is cut at its timeout. |
| 2 | Repeat 5 times. | The circuit breaker opens; the operator sees it on the integration panel. A retried webhook is processed once. |
| C1 | Remove the delay. | The breaker closes. |


## MKT-5

### M-5-01 Consignment: owner and holder are different

**Who:** admin@myplus.com (operator)  
**Before:** Phase 5. ABC Distributor places 10 phones with Shahzad Mobile Shop.  
**Covers:** MKT-R2.1, MKT-R2.2, MKT-R4.5, MKT-R4.6, MKT-R15.4, MKT-R20.5  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Create the consignment agreement, leaving one term blank → Activate. | Refused while any term is blank. |
| 2 | Fill every term → Activate. | Active. |
| 3 | Sell one phone. | Owner ABC, custodian Shahzad; ABC is owed the price less Shahzad's commission, after the sell-through report and the return period. |
| C1 | Return the phone; end the agreement. | Ended. |

### M-5-02 Shrinkage claims need evidence

**Who:** admin@myplus.com (operator)  
**Before:** Phase 5. A count finds 1 phone missing; tolerance 0%.  
**Covers:** MKT-R12.1, MKT-R12.2, MKT-R12.3  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Record the count difference; raise a claim against the custodian. | The claim needs proof and stays OPEN with a claim window. |
| 2 | Look at the custodian's statement. | No debit until the claim is approved through the dispute step. |
| C1 | Withdraw the test claim. | Withdrawn. |


## MKT-6

### M-6-01 Regulated pharmacy only after legal review

**Who:** admin@myplus.com (operator)  
**Before:** Phase 6 legal sign-off recorded.  
**Covers:** MKT-R20.6, MKT-R6.3  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Enable an approved pharmacy seller; a customer orders a prescription item. | The order cannot proceed without an uploaded prescription verified by a pharmacist. |
| 2 | Look at the order line. | Batch and expiry are on it. |
| 3 | Recall the batch. | The batch is quarantined everywhere. |
| C1 | Cancel the test order. | Cancelled. |


## Programme

### M-X-01 Who is accountable is always visible

**Who:** admin@myplus.com (operator)  
**Before:** Any order in any phase.  
**Covers:** MKT-R23.1, MKT-R3.1, MKT-R3.2  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Open the order's Parties panel. | Customer relationship: MaxTheService. Named seller, stock owner, custodian, fulfiller, carrier and warranty provider — none blank, none assumed. |
| C1 | Nothing to undo. | — |

### M-X-02 Tenant scope on every artefact

**Who:** admin@myplus.com (operator)  
**Before:** Two sellers active.  
**Covers:** MKT-R21.2, MKT-R21.3, MKT-R22.1  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Export Seller A's statement; check the file name, the report header and the notification it sent. | Each carries Seller A's organisation. |
| 2 | Export Seller B's. | It never contains Seller A's rows. |
| C1 | Delete the exported files. | — |

### M-X-03 The data model has every entity the source lists

**Who:** admin@myplus.com (operator)  
**Before:** After Phase 5.  
**Covers:** MKT-R21.1, MKT-R1.1  
**Evidence:** written from the design — not built yet

| # | Do this | Expect |
|---|---|---|
| 1 | Compare the operator's data dictionary with source §21. | All 22 entities are present or mapped to an existing table, with the mapping written down. |
| C1 | Nothing to undo. | — |

