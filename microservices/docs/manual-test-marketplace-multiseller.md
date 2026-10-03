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
2. Open the business dashboard and the Register menu.
3. Open Configuration and look for Marketplace.

**Expect:** No Marketplace entry in any menu. Configuration shows 'Sell on the MaxTheService marketplace' switched OFF.

### M-0a-02 Owner cannot switch it on before MaxTheService entitles them

**Persona:** owner.pharma@ (not entitled)  
**Pre:** Operator has not entitled owner.pharma@.  
**Covers:** MKT-R20.0, MKT-R22.1

1. Configuration → switch 'Sell on the MaxTheService marketplace' ON → Save.

**Expect:** The save is refused with a sentence that says the feature is not in the current plan. The switch goes back to OFF. No error page.

### M-0a-03 Operator entitles, owner opts in, agreements accepted

**Persona:** admin@myplus.com (operator) then owner.business@ (Seller A)  
**Pre:** Seller A has no marketplace entitlement.  
**Covers:** MKT-R9.1, MKT-R9.2, MKT-R9.3, MKT-R20.0

1. As operator: Organizations → Seller A → Entitlements → Marketplace selling → ACTIVE.
2. Log out. As Seller A: Configuration → switch Marketplace ON → Save → log out and in.
3. Open Register → Marketplace.
4. Read and accept the Seller agreement and the Data-sharing agreement.

**Expect:** Marketplace appears in the menu after re-login. Both agreements show 'Accepted v1 by owner.business@ on <today>'. The data-sharing text lists what is shared (product, price, availability, area, delivery time, warranty, returns) and what is never asked (cost, margin, other customers, staff data).

### M-0a-04 A user-tier member cannot accept agreements

**Persona:** user.business@  
**Pre:** Seller A has the module on; agreements not yet accepted.  
**Covers:** MKT-R22.1

1. Log in as user.business@ → Marketplace.

**Expect:** The agreements are shown read-only with 'Only the owner or an admin can accept'. No Accept button.

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
**Pre:** Seller A is entitled and has a catalog product 'Galaxy A32 128 Black' with stock 5.  
**Covers:** MKT-R5.1, MKT-R5.2, MKT-R6.2, MKT-R6.4

1. Marketplace → Products to publish → Propose.
2. Pick the product. Brand Samsung, Model Galaxy A32, Storage 128GB, Colour Black, Condition New, Warranty 12M.
3. Submit.

**Expect:** The proposal shows status PENDING_REVIEW and the key SAMSUNG|GALAXY-A32|128GB|BLACK|NEW|12M. The seller's own product list is unchanged.

### M-1b-02 ⚠ 64GB is never merged with 128GB

**Persona:** owner.mobile@ (Seller B)  
**Pre:** Seller A's 128GB proposal exists.  
**Covers:** MKT-R6.6, MKT-R6.1

1. As Seller B propose 'Galaxy A32 64 Black' with Storage 64GB, otherwise identical.

**Expect:** Key ends …|64GB|BLACK|NEW…. No 'matches an existing product' suggestion appears.

### M-1b-03 Same phone from a second seller is suggested, not merged

**Persona:** owner.mobile@ (Seller B)  
**Pre:** Seller A's 128GB proposal exists.  
**Covers:** MKT-R6.1, MKT-R6.4

1. As Seller B propose 'Samsung A-32 128GB blk' with Brand Samsung, Model Galaxy A32, Storage 128 GB, Colour Black, New, 12M.

**Expect:** Same key as Seller A's. Status PENDING_REVIEW with 'Possible match: Samsung Galaxy A32 128GB Black'. It is NOT live until the operator decides.

### M-1b-04 Operator matches and then corrects a bad match

**Persona:** admin@myplus.com (operator)  
**Pre:** Both proposals are in the queue.  
**Covers:** MKT-R6.5, MKT-R6.4

1. Operator console → Marketplace → Match review.
2. Match Seller B's proposal to the canonical product.
3. Reopen it → Needs correction → note 'colour is Blue on the box' → Save.

**Expect:** After matching, the canonical product lists 2 sources. After correction, Seller B's source shows NEEDS_CORRECTION with the note, and Seller B sees the note on their proposal.

### M-1b-05 Panadol 10s and 20s stay separate

**Persona:** admin@myplus.com (operator)  
**Pre:** Phase 6 only (pharmacy). Walk on a test tenant with rx blocking switched off by the operator.  
**Covers:** MKT-R6.3, MKT-R6.6

1. Propose GSK Panadol Extra 500 mg Tablet 10, then the same with pack size 20.

**Expect:** Two different keys: GSK|PANADOL-EXTRA|500MG|TABLET|10 and …|20. They never merge.


## MKT-1c

### M-1c-01 Seller creates an offer with warranty and returns

**Persona:** owner.business@ (Seller A)  
**Pre:** The 128GB product is MATCHED.  
**Covers:** MKT-R5.3, MKT-R3.1, MKT-R4.1, MKT-R7.5, MKT-R14.1

1. Marketplace → My offers → New offer.
2. Product: Samsung Galaxy A32 128GB Black. Price 52,000. Area Karachi. Warranty '12 months — authorised distributor'. Returns '7 days'.
3. Save, then Submit.

**Expect:** The offer shows PENDING_REVIEW with price Rs. 52,000. Seller, stock owner, custodian and fulfiller all read 'Seller A'.

### M-1c-02 ⚠ A prescription product cannot be offered

**Persona:** owner.business@ (Seller A)  
**Pre:** Seller A has a product with 'Prescription required' ticked.  
**Covers:** MKT-R20.2, MKT-R7.6

1. Try to propose it to the marketplace.

**Expect:** Refused: 'Prescription and restricted products cannot be sold on the marketplace yet.'

### M-1c-03 Operator approves; a price above the ceiling is refused

**Persona:** admin@myplus.com (operator) then owner.business@ (Seller A)  
**Pre:** Offer is PENDING_REVIEW. Operator ceiling for the product is Rs. 60,000.  
**Covers:** MKT-R7.4, MKT-R22.2

1. Operator: Offer approvals → Approve.
2. Seller: edit the offer price to 75,000 → Save.

**Expect:** After approval the offer is LIVE. The 75,000 save is refused with 'This offer's price is outside the allowed range.' The price stays 52,000.

### M-1c-04 Operator sets commission, discount, promotion and ranking rules

**Persona:** admin@myplus.com (operator)  
**Pre:** MKT-1c deployed.  
**Covers:** MKT-R7.4

1. Operator console → Marketplace → Rules.
2. Set commission 10% of items for Mobiles, maximum discount 15%, promotions need approval, default ranking 'Fastest delivery'.

**Expect:** Rules save with an effective date. The seller's offer screen shows 'Commission 10% of item price' read-only.

### M-1c-05 Suspending a seller takes their offers down at once

**Persona:** admin@myplus.com (operator)  
**Pre:** Seller A has a LIVE offer.  
**Covers:** MKT-R7.6

1. Operator: Sellers → Seller A → Suspend (reason 'documents expired').
2. In an incognito window, search the phone.

**Expect:** Seller A's offer is gone on the next page load. Reinstating brings it back.

### M-1c-06 Warranty is the provider's, not MaxTheService's

**Persona:** Customer (incognito window)  
**Pre:** Seller A's offer is LIVE.  
**Covers:** MKT-R14.1, MKT-R14.2

1. Open the product page and expand Warranty on Seller A's offer.

**Expect:** Shows provider 'Authorised distributor', 12 months from delivery date, covers manufacturing defects, excludes physical and water damage, 'Claim: open a MaxTheService support case'. It never says MaxTheService is the warranty provider.


## MKT-1d

### M-1d-01 One product, two sellers, from the lowest price

**Persona:** Customer (incognito window)  
**Pre:** Seller A Rs 52,000 (today, 12 months, 4.7). Seller B Rs 51,500 (tomorrow, 6 months, 4.1). Both Karachi.  
**Covers:** MKT-R5.4, MKT-R7.2

1. Go to /marketplace. Search 'Galaxy A32'.

**Expect:** One card: 'Samsung Galaxy A32 128GB Black · Available from 2 sellers · From Rs. 51,500'.

### M-1d-02 The customer chooses the order

**Persona:** Customer (incognito window)  
**Pre:** As M-1d-01.  
**Covers:** MKT-R7.1, MKT-R18.4, MKT-R7.3

1. Open the product. Sort: Lowest price. Then Fastest. Then Best warranty. Then Nearest.

**Expect:** Lowest price: Seller B first. Fastest, Best warranty, Nearest: Seller A first. The sort shown on screen is the one chosen.

### M-1d-03 ⚠ Nothing is chosen for the customer

**Persona:** Customer (incognito window)  
**Pre:** As M-1d-01.  
**Covers:** MKT-R7.3, MKT-R7.1

1. Open the product without picking an offer. Then pick Seller A.

**Expect:** Buy is disabled until an offer is chosen. Then it reads 'Buy from Seller A'.

### M-1d-04 A city with no seller says so

**Persona:** Customer (incognito window)  
**Pre:** Both offers serve Karachi only.  
**Covers:** MKT-R7.6, MKT-R20.1

1. Change city to Lahore.

**Expect:** 'No seller delivers this product to Lahore yet.' No offers, no error.

### M-1d-05 Stale stock is not shown as available

**Persona:** Customer (incognito window)  
**Pre:** Seller B's stock last synced more than 30 minutes ago (operator can force this from the test tools).  
**Covers:** MKT-R18.2, MKT-R18.5

1. Open the product.

**Expect:** Only Seller A is listed. The product card reads 'Available from 1 seller · From Rs. 52,000'.


## MKT-1e

### M-1e-01 ⚠ Buying waits for the seller, never pretends

**Persona:** Customer (incognito window) then owner.business@ (Seller A)  
**Pre:** Seller A Rs 52,000 LIVE with stock 5.  
**Covers:** MKT-R10.2, MKT-R18.5, MKT-R8.1, MKT-R10.1

1. Choose Seller A → Buy. Name Ali, phone 03001234567, address 1 Clifton Karachi, Cash on delivery → Place order.
2. Within 5 minutes, as Seller A: Marketplace → Incoming orders → Accept.

**Expect:** The customer sees 'Waiting for the seller to confirm' with the order number, never 'Confirmed' before the seller accepts. The seller sees a countdown under 5:00. After Accept, the customer's page reads 'Confirmed by Seller A'. Seller A's sales list has a new invoice for Rs. 52,000. Available stock on the offer drops from 5 to 4.

### M-1e-02 ⚠ Two sellers in one basket are refused

**Persona:** Customer (incognito window)  
**Pre:** Phase 1.  
**Covers:** MKT-R17.1, MKT-R20.2

1. Add Seller A's offer and Seller B's offer to the basket. Checkout.

**Expect:** 'Items from different sellers must be checked out separately.' Nothing is reserved and no order is created.

### M-1e-03 Seller rejects: stock comes back, customer told

**Persona:** Customer (incognito window) then owner.business@ (Seller A)  
**Pre:** Stock on the offer is 4.  
**Covers:** MKT-R10.2, MKT-R10.5

1. Customer places an order for 1.
2. Seller A: Reject → reason 'out of stock in store'.

**Expect:** Available shows 3 while waiting and 4 again after the rejection. The customer's order reads 'Cancelled: the seller could not fulfil it' and no charge stays on it.

### M-1e-04 Nobody answers: the hold expires

**Persona:** Customer (incognito window)  
**Pre:** Acceptance window 5 minutes, hold 10 minutes.  
**Covers:** MKT-R10.2, MKT-R10.5, MKT-R19.1

1. Customer places an order. Seller does nothing for 6 minutes. Seller then clicks Accept.

**Expect:** At 5 minutes the order shows EXPIRED and stock returns. The late Accept is refused: 'This order expired before it was accepted.'

### M-1e-05 A double click is one order

**Persona:** Customer (incognito window)  
**Pre:** —  
**Covers:** MKT-R22.3

1. Click Place order twice quickly (or reload during submit and resubmit).

**Expect:** One order number. One reservation. One seller notification.

### M-1e-06 Order shows its own snapshot of terms

**Persona:** Customer (incognito window)  
**Pre:** An accepted order exists.  
**Covers:** MKT-R13.3, MKT-R3.2

1. Operator changes Seller A's return policy from 7 to 3 days.
2. Customer opens the old order.

**Expect:** The old order still shows 7-day returns, the seller, stock owner, fulfiller, price, tax, commission and delivery fee as they were.

### M-1e-07 One customer account across sellers

**Persona:** Customer (incognito window)  
**Pre:** Ali has orders from Seller A and Seller B.  
**Covers:** MKT-R8.1

1. Log in to the marketplace account. Open My orders.

**Expect:** Both orders are listed under one account, each naming its seller. Support is 'MaxTheService support' on both.

### M-1e-08 Order, payment and settlement are three different facts

**Persona:** admin@myplus.com (operator)  
**Pre:** A delivered COD order.  
**Covers:** MKT-R19.1

1. Operator console → order detail.

**Expect:** Three separate badges: Order FULFILLED, Payment CAPTURED, Settlement PENDING_RETURN_WINDOW.

### M-1e-09 Only one city, cash and one online option in the pilot

**Persona:** Customer (incognito window)  
**Pre:** Pilot configuration.  
**Covers:** MKT-R20.1

1. Go to checkout.

**Expect:** City list has the pilot city only. Payment shows Cash on delivery and one online option. Delivery is 'Assigned by MaxTheService' (manual).

### M-1e-10 ⚠ Available means free stock, and the hold is on record

**Persona:** Seller A, then the operator  
**Pre:** Offer stock: 5 on hand, nothing held.  
**Covers:** MKT-R10.4, MKT-R10.3

1. Customer orders 2 and the seller has not answered yet.
2. Seller A opens Marketplace → Incoming orders → the order → Stock hold.
3. Meanwhile a second customer tries to buy 4.

**Expect:** The hold shows reservation id, order and line, offer, MERCHANT, owner and holder (Seller A), quantity 2, HELD, created and expires times (10 minutes apart). The offer now reads 3 available, not 5, so the order for 4 is refused: "This seller does not have enough stock."


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

