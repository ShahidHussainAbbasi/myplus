"""Builds Test Book section 29 (SRA) — one data structure, one markup, so every case reads the same."""
import html, sys

E = html.escape
OWNER = "owner.marketplace@myplus.com"
ADMIN = "admin.marketplace@myplus.com <span class=\"muted\">(new fixture, SRA-2)</span>"
BOOKER = "booker.marketplace@myplus.com"
GATED = 'gated'
OTHER = "demo.marketplace@myplus.com <span class=\"muted\">(a DIFFERENT business — confirm it is its own org before relying on it)</span>"

# ---- the scripted month every report figure is computed from -------------------------------------------
DATA = """
<div class="note blue">
  <span class="hd">The scripted month — every report figure below comes from these three sales</span>
  <div class="tscroll"><table>
    <thead><tr><th>Sale</th><th>How it arose</th><th>Keyed by</th><th>Rep</th><th>Customer · area</th><th>Lines</th><th>Net</th></tr></thead>
    <tbody>
      <tr><td>SRA-S1</td><td>Hand-written order, keyed by the owner</td><td>Owner</td><td>SRA Irfan</td><td>SRA Sarwar Pharmacy · Khanpur / Zahir Pir</td><td>SRA Davitran DS 100mg × 30 @ 115, bonus 3</td><td>3,450</td></tr>
      <tr><td>SRA-S2</td><td>Booker logged in and booked</td><td>Booker</td><td>SRA Booker</td><td>SRA Labaik Pharmacy · Khanpur / 1st Sector</td><td>Davitran × 10 @ 115 · SRA Cefim 400 × 5 @ 230</td><td>2,300</td></tr>
      <tr><td>SRA-S3</td><td>Walk-in at the counter</td><td>Owner</td><td>— (Counter)</td><td>SRA Model Chemist · Rahim Yar Khan / Model Town</td><td>Cefim × 2 @ 230</td><td>460</td></tr>
    </tbody>
  </table></div>
  <p><strong>Grand total 6,210.</strong> By rep: Irfan 3,450 · Booker 2,300 · Counter 460. By area: Zahir Pir 3,450 ·
    1st Sector 2,300 · Model Town 460. By city: Khanpur 5,750 · Rahim Yar Khan 460. By product: Davitran 4,600 (40 sold +
    3 bonus) · Cefim 1,610 (7). <strong>Bonus 3 units, valued at TP 100 = 300.</strong> Davitran: company <em>Davis</em>, main
    supplier <em>SRA Davis Supplier</em>, TP 100. Cefim: TP 200. Every name carries the run's stamp so cases never collide.</p>
</div>
"""

CASES = [
 # ------------------------------------------------------------------ SRA-1 areas + inline address
 ("A", "SRA-1 · Areas and the inline address form", "sra-areas.cy.js", [
  dict(id="A1", tag=GATED, t="+ New area opens a small form over the customer form, and comes back with it selected",
   why="A rep's area is where reports group sales. Typing it as free text splits one area into several (“Khanpur”, “KHANPUR(1ST)”). The way in is the purchase form's “+ New product”: a form over the one you are filling, never leaving it.",
   acc=OWNER, before=["Signed in as the owner.", "No city named <strong>Khanpur</strong> exists yet (the run uses its stamp)."],
   data=["Customer <strong>SRA Sarwar Pharmacy</strong>, contact 0303-9497362", "New city <strong>Khanpur</strong>, new area <strong>Zahir Pir</strong>"],
   steps=[("Register → Customer → <strong>New</strong>. Type the name and contact. Under <strong>Area</strong> click <strong>+ New area</strong>.",
           ["A small <strong>New area</strong> form opens OVER the customer form; the customer form and what you typed stay behind it.",
            "It offers the existing cities, and a box “…or type a new city”."]),
          ("Type <strong>Khanpur</strong> in “…or type a new city” and <strong>Zahir Pir</strong> as the area. Press <strong>Save</strong> (or Enter).",
           ["The small form closes. A message: <strong>“Zahir Pir” added and selected.</strong>",
            "On the customer form: City <strong>Khanpur</strong>, Area <strong>Zahir Pir</strong> — both selected; the name you typed is still there."]),
          ("Save the customer. Then press <strong>Edit</strong> on its row.",
           ["Saved. The grid shows <strong>Zahir Pir, Khanpur</strong> under the address.",
            "The edit form opens with City Khanpur and Area Zahir Pir already chosen."])],
   clean="Kept — later cases build on it."),
  dict(id="A2", tag=GATED, t="The area list follows the city; a duplicate selects the existing one; Esc closes only the small form",
   why="Two spellings of one area are the problem this slice exists to stop — and the dialog must never take the customer form down with it.",
   acc=OWNER, before=["A1 done.", "A second city <strong>Rahim Yar Khan</strong> with area <strong>Model Town</strong>."],
   data=["None new"],
   steps=[("Customer → New. City <strong>Rahim Yar Khan</strong>; open Area.", ["Only <strong>Model Town</strong> is offered — not Zahir Pir."]),
          ("City back to <strong>Khanpur</strong>; open Area.", ["<strong>Zahir Pir</strong> is offered again."]),
          ("+ New area → City Khanpur → type <strong>ZAHIR PIR</strong> (capitals) → Save.",
           ["Nothing new is made. Message: <strong>“Zahir Pir” already existed — selected.</strong> The Area shows Zahir Pir."]),
          ("+ New city → press <strong>Esc</strong>.", ["Only the small form closes; the customer form is still open."])],
   clean="Cancel the customer form — nothing saved."),
  dict(id="A3", tag=GATED, t="The supplier form has the same City / Area and + New links",
   why="Owner requirement R9: customers AND suppliers register their address in place.",
   acc=OWNER, before=["A1 done."], data=["Supplier <strong>SRA Davis Supplier</strong> (company SRA Davis), Area Zahir Pir"],
   steps=[("Register → Supplier → New.", ["City and Area pickers, with <strong>+ New city</strong> and <strong>+ New area</strong> under them."]),
          ("Fill the name, a company and a mobile; City Khanpur, Area Zahir Pir; Save.",
           ["Saved. The grid shows <strong>Zahir Pir, Khanpur</strong> under the supplier's address."])],
   clean="Kept (S1 buys from it)."),
  dict(id="A4", tag=GATED, t="Importing customers files them under areas — and names the new ones",
   why="A typo in an import file must be visible, not silently become a new area.",
   acc=OWNER, before=["A1 done."], data=["CSV <code>name,contact,city,area</code> with rows <code>SRA Labaik Pharmacy,…,Khanpur,1st Sector</code> and <code>SRA Model Chemist,…,Rahim Yar Khan,Model Town</code>"],
   steps=[("Register → Customer → Import. Choose the file; Import.",
           ["The answer: <strong>2 row(s) imported. 1 area created (1st Sector).</strong> Model Town already existed and is reused; no city is reported as created.",
            "Labaik is filed under 1st Sector, Model Chemist under Model Town."]),
          ("A row with an area but no city.", ["That row is refused before anything is imported: “Area '…' needs its city.”"])],
   clean="Kept — S2 and S3 sell to these customers."),
  dict(id="A5", tag=GATED, t="Another business cannot see or use our areas", warn=True,
   why="An area id typed into a request must not reach another business's records (anti-IDOR).",
   acc="owner.business@myplus.com (a different business)", before=["Note the id of area <strong>Zahir Pir</strong> from A1."], data=["None"],
   steps=[("Signed in as the other business, open Customer → New → City.", ["Khanpur and its areas are <strong>not</strong> listed."]),
          ("(Automated) save a customer with our Zahir Pir id.", ["Refused: <strong>“That area was not found.”</strong> Nothing is saved.",
            "Positive control: the same save without an area succeeds."])],
   clean="Sign out."),
 ]),
 # ------------------------------------------------------------------ SRA-1 found while building it
 ("F", "SRA-1 · Four defects found while building it — fixed (owner-approved)", "sra-areas.cy.js", [
  dict(id="F1", tag=GATED, warn=True, t="Another business cannot take over a customer or a supplier by id",
   why="⚠ Found 14 Oct by reading the save path: an edit carrying ANOTHER business's customer or supplier id found that record and saved it under the caller's business — it moved. The same shape as the school takeover (EDU-IDOR-2).",
   acc=OWNER + " and owner.business@myplus.com", before=["A customer <strong>SRA Victim</strong> and a supplier in owner.marketplace@."], data=["None"],
   steps=[("(Automated) as owner.business@, save /addCustomer with SRA Victim's id and the name HIJACKED; the same on /addVender.",
           ["Both answer <strong>NOT_FOUND</strong>. Nothing named HIJACKED appears in owner.business@."]),
          ("As owner.marketplace@, search SRA Victim.", ["Still there, same id, its own name.",
            "Positive control: the owner's own edit of it still saves."])],
   clean="Nothing to undo."),
  dict(id="F2", tag=GATED, warn=True, t="Editing a customer keeps City, CNIC and the licence",
   why="⚠ The grid row carried no City, CNIC, Licence no or Licence expiry, so the edit form left them empty — or holding the PREVIOUSLY edited customer's values — and Save wrote that back.",
   acc=OWNER, before=["A customer <strong>SRA Keep</strong> with City Khanpur, CNIC 31301-1234567-1, Licence DL-777, expiry 2027-06-30."], data=["New name <strong>SRA Keep Ltd</strong>"],
   steps=[("Customer grid → <strong>Edit</strong> on SRA Keep.", ["The form shows the CNIC 31301-1234567-1 (and the licence fields)."]),
          ("Change only the name to SRA Keep Ltd; Save; Edit it again.", ["Renamed — and City, CNIC, Licence no and expiry are all still there."])],
   clean="Nothing to undo."),
  dict(id="F3", tag=GATED, warn=True, t="Editing a branch keeps it on its company's credit account",
   why="⚠ Every customer edit wrote the credit account as empty, and the save then put the customer back on ITS OWN limit — a branch grouped under a company (B2B accounts) silently left the company's pooled limit.",
   acc=OWNER, before=["Company <strong>SRA Co</strong> (limit 100,000) and branch <strong>SRA Branch</strong> under it."], data=["Edit the branch's address"],
   steps=[("Edit SRA Branch, change the address, Save.", ["Saved."]),
          ("Open the branch's account group.", ["It is still on <strong>SRA Co</strong>'s account."])],
   clean="Nothing to undo."),
  dict(id="F4", tag=GATED, warn=True, t="Editing an outlet keeps it assigned to its rep",
   why="⚠ Every customer edit wrote the outlet's rep as empty — any profile edit unassigned the outlet from its rep. A likely contributor to the territory gate that has been red since 16 Sep.",
   acc=OWNER, before=["Outlet <strong>SRA Outlet</strong> assigned to a rep."], data=["Edit the outlet's address"],
   steps=[("Edit SRA Outlet, change the address, Save.", ["Saved."]),
          ("Open the outlet assignments.", ["Still assigned to the same rep."])],
   clean="The run unassigns it at the end."),
 ]),
 # ------------------------------------------------------------------ SRA-2 reps
 ("B", "SRA-2 · Sales reps", "sra-reps.cy.js", [
  dict(id="B1", t="Add a rep who never logs in, and one who does",
   why="Shafeeq's reps book on paper as well as by login. A rep is a person on a list; a login is optional.",
   acc=OWNER, before=["Signed in as the owner."], data=["<strong>SRA Irfan</strong>, phone 0305-3939495, no login", "<strong>SRA Booker</strong>, linked to booker.marketplace@"],
   steps=[("Team → Sales reps → New. Name <strong>SRA Irfan</strong>, phone, leave <strong>Login</strong> empty. Save.", ["Irfan is listed, Active, Login: —."]),
          ("New again: <strong>SRA Booker</strong>, Login = booker.marketplace@. Save.", ["Listed with the login shown."])],
   clean="Kept. Deactivated at the end — never deleted, so history keeps the names."),
  dict(id="B2", t="One login cannot be two reps",
   why="Otherwise a rep's login could book as either row and the report would split them.",
   acc=OWNER, before=["B1 done."], data=["None new"],
   steps=[("New rep <strong>SRA Booker Two</strong>, Login = booker.marketplace@. Save.", ["Refused: “This login is already linked to SRA Booker.” Nothing saved."])],
   clean="Cancel."),
  dict(id="B3", t="A customer's usual rep narrows the booker's outlet list", warn=True,
   why="This is the territory rule whose gate has been red since 16 Sep (D6a). A positive control stops an empty list from passing.",
   acc=OWNER + "<br>" + BOOKER, before=["A1–A4 and B1 done.", "As the booker, open New Order → Outlet: <strong>Sarwar, Labaik and Model Chemist are all listed</strong> (no assignments yet)."],
   data=["Sarwar → usual rep Irfan · Labaik → usual rep Booker · Model Chemist → none"],
   steps=[("As the owner, open each customer and set <strong>Usual rep</strong> as above. Save.", ["Each customer shows its usual rep."]),
          ("Sign in as the booker. New Order → Outlet.", ["<strong>My outlets:</strong> Labaik. <strong>Other outlets:</strong> Model Chemist (nobody's).", "<strong>Sarwar is gone</strong> — it is Irfan's."])],
   clean="Kept for SRA-3; the final clean-up clears the usual reps."),
  dict(id="B4", t="A deactivated rep leaves the pickers but keeps the history",
   why="People leave; their sales must still say who made them.",
   acc=OWNER, before=["SRA-3 C1 done (Irfan has a sale)."], data=["None new"],
   steps=[("Team → Sales reps → SRA Irfan → Deactivate.", ["Irfan shows Inactive."]),
          ("Open New Sale → Rep.", ["Irfan is not offered."]),
          ("Open invoice SRA-S1.", ["Still says <strong>Rep: SRA Irfan</strong>."]),
          ("Reactivate Irfan.", ["Back in the pickers."])],
   clean="Irfan reactivated (step 4)."),
  dict(id="B5", t="A rep cannot manage the rep list",
   why="The list decides who gets credit — a rep must not edit it. Checked on screen AND at the server.",
   acc=BOOKER, before=["Signed in as the booker."], data=["None"],
   steps=[("Open the Team menu.", ["No <strong>Sales reps</strong> entry."]),
          ("(Automated) POST a new rep as the booker.", ["403. The list is unchanged."])],
   clean="Sign out."),
 ]),
 # ------------------------------------------------------------------ SRA-3 rep + source on every sale
 ("C", "SRA-3 · The rep and the source on every order and sale", "sra-rep-on-sale.cy.js", [
  dict(id="C1", t="A hand-written order keyed by the owner: keyed by owner, booked by Irfan", warn=True,
   why="The two-people rule (R5) — and defect D1: today the dispatch invoice credits whoever dispatched it.",
   acc=OWNER, before=["SRA-1 and SRA-2 done; Sarwar's usual rep is Irfan.", "Davitran in stock (bought from SRA Davis Supplier at 100)."],
   data=["Order: Sarwar, Davitran 30 @ 115, bonus 3"],
   steps=[("Orders → New Order. Outlet <strong>SRA Sarwar Pharmacy</strong>.", ["<strong>Rep</strong> fills with <strong>SRA Irfan</strong> by itself; it can be changed.", "Source shows <strong>Rep (paper)</strong>."]),
          ("Add Davitran 30, bonus 3. Book. Confirm. Pack. Dispatch.", ["An invoice is raised at dispatch."]),
          ("Open the invoice.", ["<strong>Rep: SRA Irfan</strong> · <strong>Keyed by: the owner</strong> · total 3,450.", "The person who dispatched is <strong>not</strong> shown as the rep (positive control for D1)."])],
   clean="Kept — it is SRA-S1 of the scripted month."),
  dict(id="C2", t="A rep logs in and books: the rep is themselves, and cannot be changed",
   why="A rep must not be able to book under another rep's name.",
   acc=BOOKER, before=["Signed in as the booker; C1 done."], data=["Order: Labaik, Davitran 10 @ 115, Cefim 5 @ 230"],
   steps=[("New Order. Outlet <strong>SRA Labaik Pharmacy</strong>.", ["Rep shows <strong>SRA Booker</strong> and cannot be edited.", "Source: <strong>Rep (login)</strong>."]),
          ("Add the two lines; Book. As the owner, confirm and dispatch.", ["The invoice says Rep: SRA Booker, Keyed by: SRA Booker, total 2,300."]),
          ("(Automated) book as the booker with Irfan's rep id.", ["Refused. No order is created."])],
   clean="Kept — SRA-S2."),
  dict(id="C3", t="A walk-in at the counter has no rep",
   why="Counter sales must report as Counter, not under whoever is on the till.",
   acc=OWNER, before=["Signed in as the owner."], data=["Sale: Model Chemist, Cefim 2 @ 230, cash 460"],
   steps=[("Sale → New Sale. Customer <strong>SRA Model Chemist</strong>.", ["Rep shows <strong>— Counter</strong> (Model Chemist has no usual rep)."]),
          ("Add Cefim 2; Complete Sale; print.", ["The printed <strong>Booked By</strong> line still shows the owner — unchanged by this slice (R6).", "The invoice record: Rep —, Source Counter."])],
   clean="Kept — SRA-S3."),
  dict(id="C4", t="Correcting the rep on an invoice is recorded",
   why="A wrong rep moves commission; the change must leave a trail.",
   acc=OWNER, before=["C3 done."], data=["Change SRA-S3's rep to Irfan, then back"],
   steps=[("Open SRA-S3 → Edit → Rep = SRA Irfan → Save.", ["Saved."]),
          ("Open the audit trail for that invoice.", ["An entry: rep changed from — to SRA Irfan, by the owner, with the time."]),
          ("Set the rep back to — and save.", ["A second audit entry."])],
   clean="Rep restored (step 3)."),
  dict(id="C5", t="Sales made before this slice say “Not recorded”",
   why="No guessing: an old invoice never knew its rep. Field orders booked by login are the exception — their rep is known from the order.",
   acc=OWNER, before=["Any invoice dated before SRA-3 was deployed."], data=["None"],
   steps=[("Open an old counter invoice.", ["Rep: <strong>Not recorded</strong>."]),
          ("Open an old invoice that came from a booker's order.", ["Rep: the booker who booked it (filled from the order)."])],
   clean="Nothing to undo."),
 ]),
 # ------------------------------------------------------------------ SRA-4 the analysis report
 ("D", "SRA-4 · The sales analysis report", "sra-sales-analysis.cy.js", [
  dict(id="D1", t="Filter by rep and date",
   why="The first thing the distributor asked for.",
   acc=OWNER, before=["The scripted month (S1–S3) exists, all dated today."], data=["Period today–today"],
   steps=[("Reports → Sales analysis. Period today. Filter <strong>Rep = SRA Irfan</strong>. Show.", ["Only SRA-S1's line: Davitran 30, bonus 3, net 3,450.", "Totals row: 3,450."]),
          ("Add <strong>Rep = SRA Booker</strong> to the same filter.", ["S1 and S2 lines; total 5,750."])],
   clean="Clear the filters."),
  dict(id="D2", t="Group by Rep → Area → Product: subtotals add up",
   why="Nested grouping as Odoo does it. The check is that every level adds to the one above.",
   acc=OWNER, before=["Scripted month."], data=["Group by Rep, then Area, then Product"],
   steps=[("Group by <strong>Rep</strong>, add <strong>Area</strong>, add <strong>Product</strong>. Show.",
           ["Irfan 3,450 → Zahir Pir 3,450 → Davitran 3,450.", "Booker 2,300 → 1st Sector 2,300 → Davitran 1,150 · Cefim 1,150.", "Counter 460 → Model Town 460 → Cefim 460.", "Grand total <strong>6,210</strong>."]),
          ("Collapse Booker.", ["One line: Booker 2,300; the grand total does not change."])],
   clean="Reset the grouping."),
  dict(id="D3", t="Split the columns by rep",
   why="Rows by area, one column per rep — the shape a distributor reads at month end.",
   acc=OWNER, before=["Scripted month."], data=["Rows: City · Columns: Rep"],
   steps=[("Rows = <strong>City</strong>, Columns = <strong>Rep</strong>. Show.",
           ["Khanpur: Irfan 3,450 · Booker 2,300 · Counter — · total 5,750.", "Rahim Yar Khan: Counter 460 · total 460.", "Column totals: 3,450 · 2,300 · 460."])],
   clean="Reset."),
  dict(id="D4", t="Drill from a figure to the invoice",
   why="A total nobody can open is a total nobody can check.",
   acc=OWNER, before=["D2's grouping on screen."], data=["None"],
   steps=[("Click Booker's <strong>2,300</strong>.", ["A list of exactly <strong>1 invoice</strong> (SRA-S2) with its total."]),
          ("Click the invoice number.", ["The invoice opens; Download PDF gives the same invoice as a file."])],
   clean="Close the invoice."),
  dict(id="D5", t="Bonus is counted and valued like the SSR",
   why="The same bonus must not have two values in two reports.",
   acc=OWNER, before=["Scripted month."], data=["Filter Product = Davitran"],
   steps=[("Show columns Qty, Bonus qty, Bonus value. Filter Davitran.", ["Qty 40 · <strong>Bonus qty 3</strong> · <strong>Bonus value 300</strong> (3 × TP 100)."]),
          ("Filter <strong>Bonus = with bonus</strong>.", ["Only SRA-S1's line remains."]),
          ("Open Finance → Stock & Sales (SSR) for today, supplier SRA Davis Supplier.", ["Davitran's bonus there is also 3 / 300."])],
   clean="Clear filters."),
  dict(id="D6", t="Company and supplier are separate dimensions",
   why="Davis is the company that makes it; SRA Davis Supplier is who the distributor buys from. The SSR groups by supplier.",
   acc=OWNER, before=["Scripted month."], data=["None"],
   steps=[("Group by <strong>Company</strong>.", ["Davis 4,600 (Cefim under its own company 1,610)."]),
          ("Group by <strong>Supplier</strong>.", ["SRA Davis Supplier 4,600 — the product's main supplier."])],
   clean="Reset."),
  dict(id="D7", t="Every download matches the screen",
   why="A file that disagrees with the screen is worse than no file.",
   acc=OWNER, before=["D2's grouping on screen."], data=["None"],
   steps=[("Download <strong>Excel</strong>.", ["The .xlsx has the same grouping, subtotals and grand total 6,210; money cells are numbers, not text."]),
          ("Download <strong>CSV</strong>.", ["Same rows; opens in Excel; total 6,210."]),
          ("Download <strong>PDF</strong>.", ["Landscape; the period, filters and grouping are printed at the top; total 6,210."])],
   clean="Delete the downloaded files if you like."),
  dict(id="D8", t="Moving a customer to another area moves their sales in the report",
   why="Owner decision R8: the report follows the customer's CURRENT area. The rep does not move (it is stamped).",
   acc=OWNER, before=["Scripted month."], data=["Move Sarwar from Zahir Pir to 1st Sector, then back"],
   steps=[("Edit SRA Sarwar Pharmacy: Area = <strong>1st Sector</strong>. Save. Re-run the report grouped by Area.", ["1st Sector 5,750 · Model Town 460 · Zahir Pir gone."]),
          ("Group by Rep.", ["Unchanged: Irfan 3,450 — the rep stays with the sale."]),
          ("Move Sarwar back to Zahir Pir.", ["Zahir Pir 3,450 returns."])],
   clean="Sarwar back in Zahir Pir (step 3)."),
  dict(id="D9", t="“Sees: Own” shows a rep only their sales", warn=True,
   why="Owner rule R11: what a person sees comes from the permission set the owner gave them. The positive control proves the narrowing.",
   acc=OWNER + "<br>" + BOOKER, before=["Scripted month.", "The booker's permission set has <strong>Sales analysis: view</strong> and <strong>Sees: Own</strong>."], data=["None"],
   steps=[("As the owner (Sees: All), group by Rep.", ["Irfan, Booker and Counter all listed — 6,210."]),
          ("Sign in as the booker; open Reports → Sales analysis; group by Rep.", ["<strong>Only SRA Booker, 2,300.</strong> Irfan's 3,450 is not there."]),
          ("As the owner, change the booker's set to <strong>Sees: All</strong>; the booker reloads.", ["All three reps, 6,210 (may take up to 15 minutes to reach the session)."])],
   clean="Set the booker's set back to Sees: Own."),
  dict(id="D10", t="No permission, no report — on screen and at the server",
   why="A hidden menu proves nothing on its own.",
   acc=BOOKER, before=["Remove <strong>Sales analysis: view</strong> from the booker's set."], data=["None"],
   steps=[("As the booker, open the Reports menu.", ["No Sales analysis entry."]),
          ("(Automated) request the report and the Excel download as the booker.", ["403 for both."])],
   clean="Give the booker's set Sales analysis: view back."),
  dict(id="D11", t="Another business sees none of this",
   why="Cross-tenant: our reps, areas and sales must not appear, even by id.",
   acc=OTHER, before=["Signed in as the other business."], data=["None"],
   steps=[("Reports → Sales analysis, today, group by Rep.", ["None of SRA Irfan, SRA Booker or 6,210 appear."]),
          ("(Automated) filter by our rep id and our area id.", ["Empty result — not an error that confirms they exist."])],
   clean="Sign out."),
 ]),
]

CLEANUP = ["Void SRA-S1, S2 and S3 on screen (stock and payments reversed).",
           "Clear the usual rep on Sarwar and Labaik.",
           "Deactivate reps SRA Irfan and SRA Booker; deactivate the SRA customers, supplier and products.",
           "Deactivate areas Zahir Pir, 1st Sector, Model Town and cities Khanpur, Rahim Yar Khan (they are never deleted — invoices may name them)."]


def case(c):
    warn = " ⚠" if c.get("warn") else ""
    rows = [("Why it matters", c["why"]), ("Accounts", c["acc"]),
            ("Before you start", "<ul class=\"plain\">" + "".join(f"<li>{b}</li>" for b in c["before"]) + "</ul>"),
            ("Test data", "<ul class=\"plain\">" + "".join(f"<li>{d}</li>" for d in c["data"]) + "</ul>"),
            ("What changes", c["clean"])]
    t = "".join(f"<tr><th>{h}</th><td>{v}</td></tr>" for h, v in rows)
    steps = "".join(
        f"<li class=\"step\"><div><strong>{i}.</strong> {do}</div><div class=\"expect\"><span class=\"hd\">Expected</span>"
        f"<ul>{''.join(f'<li>{e}</li>' for e in exp)}</ul></div></li>"
        for i, (do, exp) in enumerate(c["steps"], 1))
    tag = c.get('tag')
    tag_html = '<span class="tag amber">gated — passed 14 Oct</span>' if tag == 'gated' else '<span class="tag red">not built yet</span>'
    return (f"\n    <h3 id=\"sra-{c['id'].lower()}\">{c['id']} · {c['t']}{warn} {tag_html}</h3>\n"
            f"    <div class=\"tscroll\"><table><tbody>{t}</tbody></table></div>\n"
            f"    <ol class=\"steps\">{steps}</ol>\n")


style = """    <style>
      #sra ol.steps{margin:6px 0 4px; padding-left:0; list-style:none; display:flex; flex-direction:column; gap:14px}
      #sra li.step{border:1px solid var(--line); border-radius:6px; background:var(--card); padding:12px 14px;
        display:flex; flex-direction:column; gap:8px; font-size:15.5px; line-height:1.5}
      #sra .expect{background:var(--good-wash); border-radius:4px; padding:8px 12px}
      #sra .expect .hd{font-family:Archivo,sans-serif; font-weight:600; font-size:11px; letter-spacing:.06em;
        text-transform:uppercase; color:var(--good)}
      #sra .expect ul, #sra ul.plain{margin:4px 0 0; padding-left:18px}
      #sra .muted{color:var(--ink-3); font-size:14px}
      #sra th{width:150px; white-space:nowrap}
      @media (max-width:600px){ #sra th{width:auto; white-space:normal} }
    </style>
"""

out = ['<section class="mod" id="sra">\n', style,
       '    <h2>29 · Sales by rep and area: who sold, where, and every column a dimension (SRA-1 to SRA-4) <span class="tag amber">SRA-1 gated · SRA-2–4 not built</span></h2>\n',
       '    <p><strong>14 Oct: SRA-1 is built and its gate is green</strong> — <code>cypress/e2e/business/sra-areas.cy.js</code>, A1–A5 and the four fixes F1–F4, all passing; not yet walked by hand. SRA-2 to SRA-4 below are still written '
       'from the design. Originally written <strong>from the design</strong> (<code>microservices/docs/slices/sra-sales-by-rep-and-area.md</code>) on 14 Oct 2026, '
       'before any screen exists — the screen and button names are the designed ones. When each slice is built, its Cypress '
       'run performs these exact steps, photographs each one, and this section is updated with the pictures and a pass/fail per case. '
       'Distribution tenant: <strong>Shafeeq Medicine</strong>, walked as <code>owner.marketplace@</code>.</p>\n',
       '    <div class="note amber"><span class="hd">Two people on every sale</span><p><strong>Keyed by</strong> is whoever typed it into the '
       'system (printed as “Booked By”, unchanged). <strong>Rep</strong> is who took the order from the customer — by logging in, or on paper '
       'handed to the office. Reports run on the <strong>rep</strong>. The rep is fixed on the sale when it is written; the <strong>area</strong> is the '
       'customer’s current one, so moving a customer moves their history in the report (owner’s decision).</p></div>\n',
       DATA]
for key, title, spec, cases in CASES:
    out.append(f'\n    <h3 style="margin-top:28px">{title} <span class="muted">— gate: <code>cypress/e2e/business/{spec}</code></span></h3>\n')
    out.extend(case(c) for c in cases)
out.append('\n    <div class="note blue"><span class="hd">Clean-up after the whole section</span><ol>'
           + "".join(f"<li>{x}</li>" for x in CLEANUP) + '</ol></div>\n')
out.append('    <div class="note red"><span class="hd">Fixtures this section needs that do not exist yet</span><p><code>admin.marketplace@</code> '
           '(an ADMIN member of owner.marketplace’s business, for the ladder) — added with SRA-2. <code>demo.marketplace@</code> is assumed to be '
           'a different business; confirm before relying on A5/D11.</p></div>\n')
out.append('  </section>\n\n  ')
open(sys.argv[1], 'w', encoding='utf-8').write(''.join(out))
print('cases:', sum(len(c[3]) for c in CASES))
