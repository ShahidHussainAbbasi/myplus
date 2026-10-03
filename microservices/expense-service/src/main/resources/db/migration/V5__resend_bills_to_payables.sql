-- FP-4a — finance's payables subledger now stores each bill's issued amount and due date (finance V11).
-- Design: microservices/docs/slices/fp-4-payables-reads-switch.md §3
--
-- Every existing bill (paid_from AP) is queued ONCE more for the subledger, so finance learns those fields without
-- a manual step on any deploy. The row only names the bill: the snapshot is READ when the outbox sends it
-- (ExpenseOutboxService.sendPayable), so it carries the bill as it is then. finance's intake is idempotent — a
-- repeat changes nothing but the new fields. The relay picks PENDING rows up on its schedule.
INSERT INTO expense_outbox (organization_id, user_id, voucher_id, event_type, event_key, payload, status, attempts,
                            created_at, updated_at)
SELECT v.organization_id, v.user_id, v.id, 'PAYABLE', CONCAT('EXPB-', v.organization_id, '-', v.id, '-V5'),
       CONCAT('{"voucherId":', v.id, '}'), 'PENDING', 0, NOW(), NOW()
FROM expense_voucher v
WHERE v.paid_from = 'AP' AND v.voucher_no IS NOT NULL;
