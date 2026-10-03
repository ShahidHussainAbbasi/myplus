# Manual test cases: multi-seller marketplace (MKT)

**Generated** from [`marketplace/manual-cases.json`](marketplace/manual-cases.json) by `marketplace/rtm.py`. Edit the JSON, not this file. The same cases are on the [published manual-testing page](https://claude.ai/artifact/U1FyPcwSxYsg4pGLsyFzq8), where testers record Pass / Fail / Blocked. Per GATE-RUNBOOK §6 they also belong in the product Test Book.

**How to read a case.** Persona, preconditions, numbered steps, and the expected result with the exact figure to expect. ⚠ marks a case that guards a known trap: repeat it after any nearby change. Cases for slices that are not built yet are the acceptance walk for that slice.

| Account | Password | Plays |
|---|---|---|
| `owner.business@myplus.com` | `Demo@2025!` | Seller A (retail counter; admin./user. ladder in the same org) |
| `owner.mobile@myplus.com` | `Demo@2025!` | Seller B (mobile shop: the second seller of the same phone) |
| `owner.pharma@myplus.com` | `Demo@2025!` | a tenant that is not entitled |
| `admin@myplus.com` | `Admin@2025!` | MaxTheService operator |


## MKT-0a

### M-0a-01 Marketplace is off for everyone after the deploy

**Persona:** owner.pharma@ (not entitled)  
**Pre:** Fresh deploy. Nobody has switched anything on.  
**Covers:** MKT-R20.0

1. Log in as owner.pharma@.
2. Open the business dashboard and the Sale menu.
3. Open Settings → Configuration and find 'Sell on the MaxTheService marketplace'.

**Expect:** No 'Marketplace' entry under Sale. Configuration shows 'Sell on the MaxTheService marketplace' switched OFF, with the help text 'MaxTheService approves your seller account first. Off until you switch it on.'

### M-0a-02 Owner cannot switch it on before MaxTheService entitles them

**Persona:** owner.pharma@ (not entitled)  
**Pre:** owner.pharma@ is on the FREE plan and the operator has not entitled it.  
**Covers:** MKT-R20.0, MKT-R22.1

1. Settings → Configuration → tick 'Sell on the MaxTheService marketplace'.

**Expect:** The save is refused with a sentence that says it is not included in the current plan. The box goes back to unticked. No error page.

### M-0a-03 Owner switches it on, reads the agreements and applies

**Persona:** admin@myplus.com (operator) then owner.business@ (Seller A)  
**Pre:** Seller A has no marketplace entitlement.  
**Covers:** MKT-R9.1, MKT-R9.2, MKT-R9.3, MKT-R20.0

1. As operator (if the tenant is on FREE): Organizations → Seller A → Entitlements → Marketplace selling → ACTIVE.
2. As Seller A: Settings → Configuration → tick 'Sell on the MaxTheService marketplace'. Log out and back in.
3. Sale → Marketplace.
4. Read the box. Type 'Shahzad Mobile Shop' as the name customers will see. Tick 'I have read both agreements…'. Click 'Accept and apply'.

**Expect:** Before ticking, 'Accept and apply' is greyed out. The box lists what is shared (product identity, marketplace price, availability, delivery area and time, warranty, return policy, business name) and what is never asked (supplier prices, margins, other customers, staff data, full stock history). After accepting: three lines read 'Marketplace selling is switched on.', 'Agreements accepted (version v1).' and 'MaxTheService is reviewing your seller account.'

### M-0a-04 A user-tier member cannot accept agreements

**Persona:** user.business@  
**Pre:** Seller A has the module on; agreements not yet accepted.  
**Covers:** MKT-R22.1

1. Log in as user.business@ (Seller A's org, module already on).
2. Sale → Marketplace.

**Expect:** The three status lines show. The agreement box shows the text but no Accept button, and reads 'Only the owner or an admin can accept these agreements for the business.'

### M-0a-06 ⚠ Only MaxTheService approves a seller, and a suspension says why

**Persona:** admin@myplus.com (operator), then owner.business@ (Seller A)  
**Pre:** Seller A has applied (M-0a-03).  
**Covers:** MKT-R20.1, MKT-R22.1, MKT-R19.1

1. Operator console → 'Marketplace sellers'. The 'Waiting for review' list shows 'Shahzad Mobile Shop'.
2. Click Approve.
3. Switch to 'Approved'. Type 'documents expired' in the reason box and click Suspend.
4. As Seller A: Sale → Marketplace.
5. As operator: Suspended → Reinstate.

**Expect:** Approve moves the seller to the Approved list. An Approved row offers only Suspend (never Approve or Reject). Suspend with an empty reason is refused: 'Give the seller a reason. They will see it as written.' Seller A then reads 'Suspended: documents expired'. Re-accepting the agreements does not lift the suspension. Reinstate brings back 'Approved by MaxTheService. You can list products.'

### M-0a-05 Policies exist before the first live order

**Persona:** admin@myplus.com (operator)  
**Pre:** MKT-0a deployed.  
**Covers:** MKT-R20.0, MKT-R1.3

1. Operator console → Marketplace → Policies.
2. Open each of: customer terms, returns/refunds, commission, COD, warranty, complaint process, product approval.

**Expect:** Each policy has a version, an effective date and text. None is blank. The marketplace owner and legal entity are named on the customer terms.


## MKT-1b

### M-1b-01 Seller proposes a phone for the marketplace

**Persona:** owner.business@ (Seller A)  
**Pre:** Seller A is an approved seller (MKT-0a) and has a catalogue product 'Galaxy A32 128 Black' with stock 5.  
**Covers:** MKT-R5.1, MKT-R5.2, MKT-R6.2, MKT-R6.4

1. Sale → Marketplace → 'Propose a product'.
2. Your product: 'Galaxy A32 128 Black'. Brand Samsung, Model Galaxy A32, Variant / storage '128 GB', Colour 'black', Condition New, Warranty 12M.
3. Click 'Send for review'.

**Expect:** 'Sent to MaxTheService for review.' The table shows the product with identity SAMSUNG|GALAXY-A32|128GB|BLACK|NEW|12M (the space in '128 GB' and the lower-case 'black' are normalised) and a yellow 'Waiting for review' badge. The seller's own catalogue product is unchanged.

### M-1b-02 ⚠ 64GB is never merged with 128GB

**Persona:** owner.mobile@ (Seller B)  
**Pre:** Seller A's 128GB phone is matched (M-1b-04 done first).  
**Covers:** MKT-R6.6, MKT-R6.1

1. As Seller B propose 'Galaxy A32 64 Black' with Storage 64GB, otherwise identical.

**Expect:** Identity ends …|64GB|BLACK|NEW…. The operator queue shows 'New marketplace product' for it, never 'Same identity as product #…'.

### M-1b-03 Same phone from a second seller is suggested, not merged

**Persona:** owner.mobile@ (Seller B)  
**Pre:** Seller A's 128GB proposal exists.  
**Covers:** MKT-R6.1, MKT-R6.4

1. As Seller B propose 'Samsung A-32 128GB blk' with Brand Samsung, Model Galaxy A32, Storage 128 GB, Colour Black, New, 12M.

**Expect:** Same identity as Seller A's. Status 'Waiting for review'. In the operator queue its 'Existing match' column reads 'Same identity as product #<A's product>'. Nothing is live until the operator clicks Match.

### M-1b-04 Operator matches and then corrects a bad match

**Persona:** admin@myplus.com (operator)  
**Pre:** Both proposals are in the queue.  
**Covers:** MKT-R6.5, MKT-R6.4

1. Operator console → 'Product matching'. Find Seller A's proposal.
2. Click Match (the column says 'New marketplace product').
3. Find Seller B's same-key proposal; click Match.
4. Switch to 'Matched'. On Seller A's row type 'colour is Blue on the box' and click 'Needs correction'.
5. As Seller A: Sale → Marketplace.

**Expect:** After step 2 a marketplace product 'Samsung Galaxy A32 128GB Black' exists. After step 3 both sellers point at that one product. 'Needs correction' with an empty note is refused ('Tell the seller what is wrong…'). With the note, Seller A sees a red 'Needs correction' badge and the note exactly as typed, and can fix and re-send the same row.

### M-1b-06 ⚠ A product from another shop's catalogue cannot be proposed

**Persona:** owner.mobile@ (Seller B), using a browser tool or the API  
**Pre:** Seller A's product id is known (e.g. 1234).  
**Covers:** MKT-R22.1

1. Send POST /mkt/proposeProduct with sourceProductId 1234 (Seller A's product) and any attributes.

**Expect:** Refused: 'That product is not in your catalogue.' The same sentence a non-existent id gives. Seller B's proposal list is unchanged.

### M-1b-05 Panadol 10s and 20s stay separate

**Persona:** admin@myplus.com (operator)  
**Pre:** Phase 6 only (pharmacy). Walk on a test tenant with rx blocking switched off by the operator.  
**Covers:** MKT-R6.3, MKT-R6.6

1. Propose GSK Panadol Extra 500 mg Tablet 10, then the same with pack size 20.

**Expect:** Two different keys: GSK|PANADOL-EXTRA|500MG|TABLET|10 and …|20. They never merge.


## MKT-1c

### M-1c-00 Operator creates the policies sellers choose from

**Persona:** admin@myplus.com (operator)  
**Pre:** MKT-1c deployed. No policies yet.  
**Covers:** MKT-R7.4, MKT-R14.2

1. Operator console → 'Marketplace policies'.
2. Kind Warranty. Name '12 months — authorised distributor'. Provider 'Samsung Pakistan (authorised distributor)', 12 months, covers 'Manufacturing defects', excludes 'Physical and liquid damage'. Create.
3. Kind Warranty again, leave Provider empty. Create.
4. Kind Returns, name '7 days', 7 days. Create.
5. Kind Commission, name 'Standard 8%', Charged on 'Item price (not delivery)', rate 8, tick 'Use for newly approved offers'. Create.

**Expect:** Three policies are listed. Step 3 is refused: 'Name the warranty provider. MaxTheService is never assumed to be it.' The commission row shows 8% and 'default'. No policy has an Edit button, only Deactivate.

### M-1c-01 Seller creates an offer and sends it for approval

**Persona:** owner.business@ (Seller A)  
**Pre:** Seller A is an approved seller and Samsung Galaxy A32 128GB Black is MATCHED (M-1b-04). Seller A has 5 in stock. M-1c-00 done.  
**Covers:** MKT-R5.3, MKT-R7.5, MKT-R14.1

1. Sale → Marketplace → My offers → New offer.
2. Product: Samsung Galaxy A32 128GB Black. Price 52000. Delivery in 24 hours. Cities 'Karachi, karachi , Lahore'. Warranty '12 months — authorised distributor'. Returns '7 days'.
3. Click 'Save and send for approval'.

**Expect:** The row shows Rs. 52,000, cities 'Karachi, Lahore' (the duplicate is dropped, spelling kept as typed) and a 'Waiting for review' badge. 'Send for approval' without a warranty or return policy is refused: 'Choose a warranty and a return policy before sending the offer for approval.'

### M-1c-02 ⚠ The browser cannot choose the stock owner

**Persona:** owner.business@ (Seller A), using the browser developer tools or the API  
**Pre:** M-1c-01 done; the offer id is known.  
**Covers:** MKT-R3.1, MKT-R3.2, MKT-R4.1, MKT-R22.1, MKT-R20.2

1. Send POST /mkt/saveOffer {id: <offer>, sellerOrganizationId: 999999, stockOwnerOrganizationId: 999999, fulfillerOrganizationId: 999999}.
2. Send GET /mkt/getOffer?id=<offer>.
3. Send POST /mkt/saveOffer {id: <offer>, stockSourceType: 'SUPPLIER'}.

**Expect:** Step 1 succeeds but every party id is still Seller A's own organisation and the price is still 52,000 (a partial edit keeps what it does not mention). Step 3 is refused: 'Offers from supplier stock are not available yet.' The source stays MERCHANT.

### M-1c-03 ⚠ A prescription product cannot be offered

**Persona:** owner.business@ (Seller A)  
**Pre:** Seller A has a product with 'Prescription required' ticked.  
**Covers:** MKT-R20.2, MKT-R7.6

1. Sale → Marketplace → Propose a product → choose it → Send for review.

**Expect:** Refused: 'Prescription and restricted products cannot be sold on the marketplace yet.' Nothing is added to the proposals table.

### M-1c-04 Operator approves; price outside the limits is refused

**Persona:** admin@myplus.com (operator) then owner.business@ (Seller A)  
**Pre:** The offer is waiting for review. A default commission policy exists (M-1c-00).  
**Covers:** MKT-R7.4, MKT-R22.2

1. Operator console → 'Offer approvals' → Approve on Seller A's row.
2. Operator: POST /platform/mkt/productLimits {id: <marketplace product>, priceFloor: 40000, priceCeiling: 60000}.
3. Seller: My offers → Edit → price 75000 → Save.
4. Seller: price 51500 → Save.

**Expect:** After step 1 the row leaves the queue and the seller sees 'Live'. Approving with no default commission policy is refused: 'Set a default commission policy before approving offers.' Step 3 is refused with 'This offer's price is outside the allowed range…' and the price stays 52,000. Step 4 saves.

### M-1c-05 Suspending a seller takes their offers down at once

**Persona:** admin@myplus.com (operator) + a customer in an incognito window  
**Pre:** Seller A's offer is Live.  
**Covers:** MKT-R7.6

1. Incognito: open /marketplace/public/products/<id>/offers?city=Karachi. Seller A is listed.
2. Operator: Marketplace sellers → Seller A → Suspend (reason 'documents expired').
3. Incognito: reload.
4. Operator: Reinstate. Incognito: reload.

**Expect:** After the suspension Seller A's offer is gone on the next load. Reinstating brings it back. With ?city=Quetta it is never listed.

### M-1c-06 Customers see the provider's warranty and nothing internal

**Persona:** Customer (incognito window)  
**Pre:** Seller A's offer is Live.  
**Covers:** MKT-R14.1, MKT-R14.2, MKT-R9.2, MKT-R9.3

1. Open /marketplace/public/products/<id>/offers?city=Karachi and read Seller A's row.

**Expect:** warrantyProvider 'Samsung Pakistan (authorised distributor)', 12 months, starts on DELIVERY, covers/excludes as set, 7 return days. There is no cost, margin, purchase, supplier or stock-movement field, and no organisation id other than the seller's.

### M-1c-07 Deactivating a policy never changes what was sold

**Persona:** admin@myplus.com (operator) then owner.business@ (Seller A)  
**Pre:** Seller A's offer uses '7 days'.  
**Covers:** MKT-R7.4

1. Operator: Marketplace policies → '7 days' → Deactivate.
2. Seller: My offers → Edit. Open the Returns list.
3. Seller: POST /mkt/saveOffer {id: <offer>, returnPolicyId: <the 7-day id>}.

**Expect:** The existing offer still names the 7-day policy. The Returns list no longer offers it. Step 3 is refused: 'Choose an active return policy.'

### M-1c-08 ⚠ Another seller cannot see or change the offer

**Persona:** owner.mobile@ (Seller B), approved seller  
**Pre:** Seller A's offer id is known.  
**Covers:** MKT-R22.1

1. GET /mkt/myOffers (positive control).
2. GET /mkt/getOffer?id=<A's offer>.
3. POST /mkt/saveOffer {id: <A's offer>, marketplacePrice: 1}.
4. POST /platform/mkt/decideOffer {id: <A's offer>, decision: 'SUSPEND', note: 'x'}.

**Expect:** Step 1 works. Steps 2 and 3 answer 'No such offer.' (the same as a non-existent id). Step 4 is refused (not the operator). Seller A's offer is unchanged.


## MKT-1d

### M-1d-01 One product, two sellers, from the lowest price

**Persona:** Customer (incognito window)  
**Pre:** Seller A: Samsung Galaxy A32 128GB Black at Rs 52,000, delivery 4 hours, 12-month warranty (Samsung Pakistan), 7-day returns. Seller B: the SAME product at Rs 51,500, 24 hours, 6-month warranty. Both Live, both deliver to Karachi only (M-1c-01…04 done for each).  
**Covers:** MKT-R5.4, MKT-R7.2, MKT-R18.2

1. Open /marketplace. City: Karachi. Search 'Galaxy A32'.
2. Open the card.
3. Press the browser's Back button.

**Expect:** One card: 'Samsung Galaxy A32 128GB Black · From Rs. 51,500 · Available from 2 sellers · Delivery in 4 hours'. The product page lists 2 sellers, each with price, delivery promise, warranty months and provider, return days, 'No ratings yet' and 'Stock checked … ago'. Back returns to the same results.

### M-1d-02 The customer chooses the order

**Persona:** Customer (incognito window)  
**Pre:** As M-1d-01.  
**Covers:** MKT-R7.1, MKT-R18.4

1. On the product page: Sort sellers by Lowest price. Then Fastest delivery. Then Longest warranty.
2. With Lowest price chosen, reload the page.
3. Copy the address into another browser.

**Expect:** Lowest price: Seller B first. Fastest delivery and Longest warranty: Seller A first. After reload and in the other browser the sort is still Lowest price with Seller B first (the choice lives in the address). Nearest, rating and promotion are not offered yet: there is no data behind them.

### M-1d-03 ⚠ Nothing is chosen for the customer

**Persona:** Customer (incognito window)  
**Pre:** As M-1d-01, sorted by Lowest price.  
**Covers:** MKT-R7.3, MKT-R7.1

1. Look at the button at the bottom before choosing.
2. Using only the keyboard (Tab to the list, arrow keys or Space), choose Seller A, the dearer one.
3. Press the button.

**Expect:** Before choosing: no seller is selected and the button reads 'Choose a seller first' and is disabled. After choosing Seller A: the row is highlighted and the button reads 'Buy from <Seller A's name>'. Pressing it says 'Ordering opens soon. You chose <Seller A> at Rs. 52,000.' (checkout is MKT-1e).

### M-1d-04 A city with no seller says so

**Persona:** Customer (incognito window)  
**Pre:** Both offers serve Karachi only.  
**Covers:** MKT-R7.6, MKT-R20.1

1. On the product page change the city to Lahore.
2. Go back to the search, keep Lahore, search 'Galaxy A32'.

**Expect:** Product page: 'No seller delivers this product to Lahore yet.', no rows, the button stays disabled. Search: no card for the phone, and 'No products match in Lahore. Try fewer words or another city.' No error.

### M-1d-05 Stale or paused offers leave the card and the table together

**Persona:** Customer (incognito window) + owner.mobile@ (Seller B)  
**Pre:** As M-1d-01.  
**Covers:** MKT-R7.6, MKT-R18.2, MKT-R18.5, MKT-R5.4

1. Seller B: Marketplace → My offers → Pause.
2. Customer: search again and open the product.
3. Seller B: Resume. If the test tools can age Seller B's stock check beyond 30 minutes, do that and repeat step 2.

**Expect:** With Seller B paused (or its stock unconfirmed for 30 minutes) the card reads 'From Rs. 52,000 · Available from 1 seller' and the product page lists only Seller A. The card's number always equals the rows on the page. After Resume both are back.

### M-1d-06 The operator chooses the order customers see first

**Persona:** admin@myplus.com (operator), then Customer (incognito window)  
**Pre:** As M-1d-01.  
**Covers:** MKT-R7.4, MKT-R18.4

1. Operator console → Marketplace policies → 'Order customers see first' → Lowest price → Save.
2. Customer: open the product without choosing a sort.
3. Operator: set it back to Recommended.

**Expect:** Saved. The customer's page opens with 'Lowest price' selected and Seller B first. The customer can still change it. A business owner (not the operator) cannot read or change this setting.

### M-1d-07 Works in every language, on a phone, by keyboard

**Persona:** Customer (incognito window) on a phone (or a 375 px wide window)  
**Pre:** As M-1d-01.  
**Covers:** MKT-R7.2

1. Open /marketplace?lang=ur and search, then open the product.
2. Switch to English with the language link at the top.
3. Tab through the whole page without a mouse.

**Expect:** Urdu and Arabic read right to left with prices and sellers mirrored; switching language keeps the page you are on. Nothing scrolls sideways. Every control has a visible label and a visible focus ring; buttons are at least finger-sized.

### M-1d-08 ⚠ Search text is just text

**Persona:** Customer (incognito window)  
**Pre:** As M-1d-01.  
**Covers:** MKT-R7.6

1. Search for: %
2. Search for: _
3. Search for: ' OR 1=1 --
4. Open /marketplace?product=999999999

**Expect:** Each search answers normally ('No products match…'); none lists every product. The unknown product reads 'No such product.' with no error page.


## MKT-1e

### M-1e-01 ⚠ Buying waits for the seller, never pretends; the sale lands in the seller's books

**Persona:** Customer (incognito window) then owner.business@ (Seller A)  
**Pre:** Seller A has a Live offer: Samsung Galaxy A32 128GB Black, Rs 52,000, Karachi, 5 in stock, cash on delivery accepted (M-1c-01…04).  
**Covers:** MKT-R10.2, MKT-R18.5, MKT-R10.1, MKT-R1.3

1. Open /marketplace, city Karachi, open the phone, choose Seller A, press 'Buy from Seller A'.
2. Fill name Ali, phone 0300-123 4567, address 1 Clifton. Check the total. Press 'Place order'.
3. As Seller A: Sale → Marketplace → Incoming marketplace orders. Press Accept.
4. Back in the customer window, wait up to 10 seconds.

**Expect:** Step 2: 'Waiting for <Seller A> to confirm', an order number MKT-…, and '<Seller A> has 4:5x to confirm. Your stock is held.' The address bar shows ?order=MKT-… and no phone number. Step 3: the row shows a countdown under 5:00; after Accept it reads 'Accepted', 'Invoice INV-…' and 'In your orders as SO-…'. Seller A's Orders list and sales show the invoice at Rs 52,000 (the marketplace price). Step 4: the customer's page turns to 'Confirmed by <Seller A>' with '… will deliver and collect Rs. 52,000 in cash.' by itself.

### M-1e-02 One checkout is one seller

**Persona:** Customer (incognito window), using the browser developer tools or the API  
**Pre:** Seller A has a Live offer: Samsung Galaxy A32 128GB Black, Rs 52,000, Karachi, 5 in stock, cash on delivery accepted (M-1c-01…04).  
**Covers:** MKT-R17.1, MKT-R20.2

1. Try to choose two sellers' offers on the product page.
2. Send POST /marketplace/public/checkout with an offerId and also a 'lines' array naming another seller's offer.

**Expect:** Only one seller can be chosen (radio buttons). The extra 'lines' field is ignored: the order has exactly one seller order, for the offer named. Mixing sellers in one checkout waits for Phase 2.

### M-1e-03 ⚠ Seller rejects: stock comes back, customer told

**Persona:** Customer (incognito window) then owner.business@ (Seller A)  
**Pre:** Seller A has a Live offer: Samsung Galaxy A32 128GB Black, Rs 52,000, Karachi, 5 in stock, cash on delivery accepted (M-1c-01…04).  
**Covers:** MKT-R10.2, MKT-R10.5, MKT-R10.3

1. Customer orders all 5.
2. Another customer (another window, another phone) tries to order 1.
3. Seller A: Reject with an empty reason, then with 'out of stock in store'.
4. The second customer tries again.

**Expect:** Step 2 is refused: 'This seller no longer has enough stock. Please choose another offer.' (the 5 are held). Step 3: an empty reason is refused ('Give a reason…'); with the reason the row reads 'Rejected'. The first customer's page reads 'Cancelled' and 'The seller could not fulfil this order.' Step 4 succeeds: the stock came back.

### M-1e-04 Nobody answers: the hold expires

**Persona:** admin@myplus.com (operator), Customer (incognito window), owner.business@ (Seller A)  
**Pre:** Seller A has a Live offer: Samsung Galaxy A32 128GB Black, Rs 52,000, Karachi, 5 in stock, cash on delivery accepted (M-1c-01…04).  
**Covers:** MKT-R10.2, MKT-R10.5, MKT-R19.1, MKT-R7.4

1. Operator: Marketplace policies → 'Minutes a seller has to accept an order' = 1 → Save.
2. Customer places an order. Seller does nothing.
3. After about 2 minutes, look at the customer's page and the seller's list.
4. Seller presses Accept on that order.
5. Operator: set the window back to 5.

**Expect:** After the minute plus the 30-second grace and a sweep: the customer's page reads 'Cancelled' and 'The seller did not confirm in time.'; the seller's row reads 'Expired'; the stock is free again. The late Accept is refused: 'This order expired before it was accepted.' An accept that started before the deadline is never cut off.

### M-1e-05 A double click, or a lost answer, is one order

**Persona:** Customer (incognito window)  
**Pre:** Seller A has a Live offer: Samsung Galaxy A32 128GB Black, Rs 52,000, Karachi, 5 in stock, cash on delivery accepted (M-1c-01…04).  
**Covers:** MKT-R22.3

1. Fill the checkout and double-click 'Place order'.
2. Place another order, and switch the network off in the developer tools just as you press the button; switch it back on and press again.

**Expect:** Each attempt produces one order number, one seller order and one hold. After the network failure the page says 'We could not confirm your order. Press the button again; it will not be placed twice.' and pressing again shows the same order.

### M-1e-06 The order keeps its own copy of the terms

**Persona:** Customer (incognito window) + admin@myplus.com (operator)  
**Pre:** An order from M-1e-01 exists.  
**Covers:** MKT-R13.3, MKT-R3.2, MKT-R3.1

1. Operator: deactivate the 7-day return policy the offer uses (Marketplace policies → Deactivate).
2. Customer reopens the order page.

**Expect:** The order still shows '12 months warranty · Samsung Pakistan · Returns within 7 days' and Rs 52,000: the terms are copied onto the order when it is placed. Seller A's incoming row shows the commission terms it was charged under; the customer never sees commission.

### M-1e-08 Order, payment and seller answer are separate facts

**Persona:** admin@myplus.com (operator)  
**Pre:** Orders in each state exist (M-1e-01, -03, -04).  
**Covers:** MKT-R19.1, MKT-R22.1

1. Operator console → Marketplace orders. Switch between Waiting for a seller, Confirmed, Cancelled, All.

**Expect:** Each order shows its own status, the seller, the items, the city, and for cancelled ones the reason. A cash order stays payment UNPAID until delivery (settlement is MKT-1g). A business owner cannot open this list.

### M-1e-09 Phase 1 is cash on delivery; a seller who takes no cash is not offered

**Persona:** Customer (incognito window) + owner.business@ (Seller A)  
**Pre:** Seller A has a Live offer: Samsung Galaxy A32 128GB Black, Rs 52,000, Karachi, 5 in stock, cash on delivery accepted (M-1c-01…04).  
**Covers:** MKT-R20.1

1. Look at the checkout's payment line.
2. Seller A: switch off cash on delivery in the store's delivery settings. Customer tries to order again.

**Expect:** Checkout says 'Cash on delivery: you pay the seller when the order arrives. Nothing is charged now.' With COD off: 'This seller does not accept cash on delivery yet. Please choose another offer.' Online payment arrives with MKT-1e2.

### M-1e-10 ⚠ Phones need their IMEI; nothing is let go before it is given

**Persona:** owner.business@ (Seller A)  
**Pre:** Seller A's phone product is set to 'requires serial number'; an order for 2 is waiting.  
**Covers:** MKT-R10.4, MKT-R10.1

1. Press Accept without typing IMEIs.
2. Type one IMEI and press Accept.
3. Type both IMEIs and press Accept.

**Expect:** Steps 1 and 2 are refused with 'Enter the serial number (IMEI) of each unit you are sending: 2 for …'; the order stays waiting and the stock stays held. Step 3 accepts; the invoice records those two IMEIs as sold.

### M-1e-11 ⚠ No one can hold a shop's stock hostage

**Persona:** Customer (incognito window)  
**Pre:** Seller A has a Live offer: Samsung Galaxy A32 128GB Black, Rs 52,000, Karachi, 5 in stock, cash on delivery accepted (M-1c-01…04).  
**Covers:** MKT-R22.3, MKT-R22.1

1. With one phone number, place 3 orders and leave them unanswered.
2. Place a 4th with the same number written differently (e.g. (0300) 1234567).
3. Place one with a different number.

**Expect:** The 4th is refused: 'You already have 3 orders waiting for sellers to confirm. Please wait for an answer first.' A different number works. A page left open long enough to lose its security token says 'This page expired. Please reload it and try again.'


## MKT-1e2

### M-1e-07 One customer account across sellers (MKT-1e2)

**Persona:** Customer (incognito window)  
**Pre:** Ali has orders from Seller A and Seller B.  
**Covers:** MKT-R8.1

1. Log in to the marketplace account. Open My orders.

**Expect:** Both orders are listed under one account, each naming its seller. Support is 'MaxTheService support' on both.


## MKT-1f

### M-1f-01 One place to complain

**Persona:** Customer (incognito window) → admin@myplus.com (operator) → owner.business@ (Seller A)  
**Pre:** Delivered order from Seller A.  
**Covers:** MKT-R8.2, MKT-R8.1, MKT-R23.1

1. Customer: My orders → the order → Get help → 'Item not as described' → 'Box says 64GB' → Send.
2. Operator: Support cases → the case → Task seller.
3. Seller A: Marketplace → Tasks.

**Expect:** Customer sees 'MaxTheService support is looking into this' and never Seller A's phone number. The seller sees the task with the order number. The operator's reply reaches the customer from MaxTheService.

### M-1f-02 Return cost follows the cause

**Persona:** admin@myplus.com (operator)  
**Pre:** A return request for each reason below on test orders.  
**Covers:** MKT-R13.1

1. Approve returns with reasons: Wrong product, Damaged before handover, Defective, Not as described, Change of mind, Delivery failure, Routing error.

**Expect:** Cost bearer reads, in order: Fulfiller, Custodian, Stock owner, Seller, Customer, Carrier, MaxTheService.

### M-1f-03 Return flow end to end

**Persona:** Customer (incognito window) → admin@myplus.com (operator) → owner.business@ (Seller A)  
**Pre:** Delivered order Rs 52,000.  
**Covers:** MKT-R13.2

1. Customer requests a return (Defective).
2. Operator checks policy → arranges pickup.
3. Seller inspects → Approve.
4. Choose Restock / Quarantine / Write off.

**Expect:** Refund Rs 52,000 approved once. Stock goes to the chosen bucket. Seller's statement shows a REFUND line −52,000 and the commission reversal.

### M-1f-04 ⚠ Unsafe or expired goods escalate at once

**Persona:** Customer (incognito window)  
**Pre:** —  
**Covers:** MKT-R13.4

1. Get help → 'Expired or unsafe' → Send.

**Expect:** The case is URGENT and at the top of the operator's queue immediately.

### M-1f-05 Every action leaves a trail

**Persona:** admin@myplus.com (operator)  
**Pre:** After M-1f-01..04.  
**Covers:** MKT-R22.4

1. Operator console → Audit log → filter 'Marketplace'.

**Expect:** Each case, task, return decision and refund is listed with who, when and the order number.


## MKT-1g

### M-1g-01 The worked example adds up

**Persona:** owner.business@ (Seller A)  
**Pre:** A delivered Rs 5,000 order, fixed commission Rs 500, delivery Rs 200, processing Rs 50, reserve Rs 100.  
**Covers:** MKT-R15.5, MKT-R16.1

1. Marketplace → Settlement statement → open the line.

**Expect:** Customer paid 5,000.00 · Commission 500.00 · Delivery 200.00 · Processing 50.00 · Reserve 100.00 · Payable 4,150.00. The line total checks: 4,150 + 500 + 200 + 50 + 100 = 5,000.

### M-1g-02 Nothing is payable before delivery and the return window

**Persona:** owner.business@ (Seller A)  
**Pre:** An order placed today, not delivered.  
**Covers:** MKT-R15.2, MKT-R15.3, MKT-R16.2

1. Settlement statement.

**Expect:** The line is NOT_ELIGIBLE. After delivery: PENDING_RETURN_WINDOW. After the window: ELIGIBLE.

### M-1g-03 T+1 skips the weekend

**Persona:** owner.business@ (Seller A)  
**Pre:** Delivery recorded on a Friday, trigger DELIVERED, T+1.  
**Covers:** MKT-R15.1

1. Settlement statement → the line's 'Payable on' date.

**Expect:** Payable on the following Monday (Tuesday if Monday is a bank holiday).

### M-1g-04 ⚠ Payouts need two people and happen once

**Persona:** admin@myplus.com (operator) (two operator accounts)  
**Pre:** Seller A has an ELIGIBLE balance.  
**Covers:** MKT-R16.3, MKT-R22.3

1. Operator 1: Payouts → Request for Seller A.
2. Operator 1 tries to approve it.
3. Operator 2 approves; Operator 1 marks paid with bank reference TRX-123.
4. Refresh and click Request again.

**Expect:** Self-approval refused: 'Another person must approve this payout.' Paid payout shows TRX-123. The second request reuses the same payout and does not create a second one.

### M-1g-05 Mistakes are reversed, not edited

**Persona:** admin@myplus.com (operator)  
**Pre:** A PAID line.  
**Covers:** MKT-R15.6, MKT-R16.2

1. Look for an Edit action on any ledger line.
2. Record a correction.

**Expect:** No Edit. The correction appears as a new ADJUSTMENT or REVERSAL line. The original is unchanged.

### M-1g-06 Commission reaches the books

**Persona:** admin@myplus.com (operator)  
**Pre:** Note the trial balance line 'Marketplace commission' before.  
**Covers:** MKT-R15.5, MKT-R1.3

1. Settle one delivered order with commission Rs 480.
2. Open Finance → Trial balance.

**Expect:** Marketplace commission is higher by exactly 480.00. The seller payable control account moved by the payable.


## MKT-2

### M-2-01 Multi-seller basket splits into seller orders

**Persona:** Customer (incognito window)  
**Pre:** Phase 2 enabled.  
**Covers:** MKT-R17.2, MKT-R20.3

1. Buy Seller A's and Seller B's offers in one checkout.

**Expect:** One order number with two parts, each naming its seller, delivery promise and delivery fee. Cancelling one part leaves the other.

### M-2-02 Shortage: same phone moves only on the same or better terms

**Persona:** Customer (incognito window) / owner.business@ (Seller A)  
**Pre:** Phase 2. Seller B has the same phone cheaper and faster.  
**Covers:** MKT-R11.1, MKT-R11.2

1. Seller A rejects for shortage.

**Expect:** The order moves to Seller B silently and reads 'Now from Seller B'. If Seller B were dearer or slower, the customer would be asked first.

### M-2-03 ⚠ Never a different variant without asking

**Persona:** Customer (incognito window)  
**Pre:** Phase 2. Only a 64GB is available.  
**Covers:** MKT-R11.3

1. Seller rejects the 128GB line for shortage.

**Expect:** Customer is asked 'Accept 64GB instead?' Nothing changes until they answer. Declining cancels the line and refunds it.

### M-2-04 Shortage cause and responsibility are recorded

**Persona:** admin@myplus.com (operator)  
**Pre:** After M-2-02.  
**Covers:** MKT-R11.4, MKT-R12.4

1. Operator → order → Shortage panel.

**Expect:** Shows cause (merchant stock not updated), responsible party (Seller A), effect on seller performance. No money taken from the seller without the dispute step.

### M-2-05 Checkout never hangs on slow sellers

**Persona:** Customer (incognito window)  
**Pre:** Phase 2. One seller's system is slow (operator test toggle).  
**Covers:** MKT-R18.1, MKT-R18.3, MKT-R18.5

1. Checkout an item whose top offer belongs to the slow seller.

**Expect:** Within about 2 seconds the next seller is tried, or the screen says 'We are checking availability'. It never spins without end.

### M-2-06 Acceptance terms differ by order value

**Persona:** admin@myplus.com (operator)  
**Pre:** Phase 2. Rule: orders above Rs 100,000 get 15 minutes.  
**Covers:** MKT-R10.6

1. Place a Rs 150,000 order.

**Expect:** The seller's countdown starts at 15:00.


## MKT-3

### M-3-01 Platform stock from the MaxTheService warehouse

**Persona:** admin@myplus.com (operator) / Customer (incognito window)  
**Pre:** Phase 3.  
**Covers:** MKT-R4.2, MKT-R20.4, MKT-R1.1, MKT-R1.2

1. Warehouse receives 100 education kits. Customer buys one.

**Expect:** Offer reads 'Sold and shipped by MaxTheService'. Picking and packing happen in the warehouse screens. Stock 100 → 99.


## MKT-4

### M-4-01 Supplier stock is never 'in stock today' without a promise

**Persona:** Customer (incognito window)  
**Pre:** Phase 4. Distributor offer with a 20-minute acceptance window.  
**Covers:** MKT-R4.3, MKT-R4.4, MKT-R20.5

1. Open the product.

**Expect:** The supplier offer shows 'Ships from supplier · confirmation within 20 minutes', never 'In stock, today'.

### M-4-02 A slow supplier API does not hang checkout

**Persona:** admin@myplus.com (operator)  
**Pre:** Phase 4. Supplier endpoint delayed 5 s.  
**Covers:** MKT-R22.5

1. Checkout the supplier offer.

**Expect:** Answer within the routing deadline. The supplier call is cut at its timeout and the breaker opens after repeated failures. The operator sees it on the integration panel. A retried webhook is processed once.


## MKT-5

### M-5-01 Consignment: owner and holder are different

**Persona:** admin@myplus.com (operator)  
**Pre:** Phase 5. ABC Distributor (consignor) places 10 phones with Shahzad Mobile Shop (consignee).  
**Covers:** MKT-R2.1, MKT-R2.2, MKT-R4.5, MKT-R4.6, MKT-R15.4, MKT-R20.5

1. Create the consignment agreement with every required term.
2. Sell one phone.

**Expect:** The agreement cannot be activated while any term is blank. After the sale: owner ABC, custodian Shahzad. Settlement shows ABC entitled to the price less Shahzad's commission, after the sell-through report and return period.

### M-5-02 Shrinkage claims need evidence

**Persona:** admin@myplus.com (operator)  
**Pre:** Phase 5. Count finds 1 phone missing, tolerance 0%.  
**Covers:** MKT-R12.1, MKT-R12.2, MKT-R12.3

1. Record the count difference. Raise a claim against the custodian.

**Expect:** The claim needs proof and stays OPEN with a claim window. No debit appears until it is approved through the dispute step. Defaults: consigned stock at merchant → custodian responsible.


## MKT-6

### M-6-01 Regulated pharmacy only after legal review

**Persona:** admin@myplus.com (operator)  
**Pre:** Phase 6 legal sign-off recorded.  
**Covers:** MKT-R20.6, MKT-R6.3

1. Enable an approved pharmacy seller. A customer orders a prescription item.

**Expect:** The order cannot proceed without an uploaded prescription verified by a pharmacist. Batch and expiry are on the order. A recall quarantines the batch.


## Programme

### M-X-01 Who is accountable is always visible

**Persona:** admin@myplus.com (operator)  
**Pre:** Any order in any phase.  
**Covers:** MKT-R23.1, MKT-R3.1, MKT-R3.2

1. Open the order's Parties panel.

**Expect:** Customer relationship: MaxTheService. Plus named seller, stock owner, custodian, fulfiller, carrier and warranty provider. None is blank, none is assumed.

### M-X-02 Tenant scope on every artefact

**Persona:** admin@myplus.com (operator)  
**Pre:** Two sellers active.  
**Covers:** MKT-R21.2, MKT-R21.3, MKT-R22.1

1. Export Seller A's statement. Check the file name, the report header and the notification it triggered.

**Expect:** Each carries Seller A's org. Seller B's export never contains Seller A's rows.

### M-X-03 The data model has every entity the source lists

**Persona:** admin@myplus.com (operator)  
**Pre:** After Phase 5.  
**Covers:** MKT-R21.1, MKT-R1.1

1. Compare the operator's data dictionary page with source §21.

**Expect:** All 22 entities are present or mapped to an existing table, with the mapping written down.

