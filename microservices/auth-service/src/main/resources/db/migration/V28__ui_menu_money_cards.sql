-- HMS RW-4a — the dashboard's MONEY cards are rows too (client, 2026-10-12: the front desk was looking at the shop's
-- revenue and stock value). Each card names its key in the page; the code that shows it is here, never in the page.
-- business-service leaves the same figures out of its answer for anyone without report.view (the server is the
-- control; the card is the convenience). The owner and an administrator see every card.
-- (No dollar-brace anywhere in this file: Flyway would read it as a placeholder, even in a comment.)

INSERT INTO ui_menu (module, menu_key, parent_key, label_key, codes, capability, sort_order)
SELECT v.module, v.k, NULL, v.l, 'report.view', NULL, v.o FROM (
          SELECT 'BUSINESS' AS module, 'kpi.stockValue' AS k, 'ui.stockValueAtCost' AS l, 950 AS o
UNION ALL SELECT 'BUSINESS', 'kpi.sales',   'ui.salesThisMonth',   951
UNION ALL SELECT 'BUSINESS', 'kpi.revenue', 'ui.revenueThisMonth', 952
) v
WHERE NOT EXISTS (SELECT 1 FROM ui_menu m WHERE m.menu_key = v.k);
