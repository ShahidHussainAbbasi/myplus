# Slice 24 — Pagination for business-service list reads

Status: **DONE + VERIFIED** ✅ (2026-06-13). Tech-debt #4 (🟠). Build ✓, headed Cypress
sell/customer/item/flow 73/73 (backward-compatible, no regression). UI follow-up (server-side
DataTables) still open. Follows the slice cadence.

## Document — what & why
`findScoped` / `getAll*` return a tenant's **entire** table in one response. For a busy org this loads
every customer/item/sell row into memory per call → latency + OOM risk. Add **server-side pagination**.

### Constraint that shapes the design
The monolith renders these lists with **client-side DataTables**, which expect the *full* array in the
`GenericResponse` and do paging in the browser. Changing the response shape would break that UI. So
pagination here is **backward-compatible and opt-in**:
- **No `page`/`size` params** → behaves exactly as today (full list). The existing UI is untouched.
- **`page` & `size` provided** → returns just that page (repo `Pageable`, newest/id order). New/large-tenant
  callers (and a future server-side-DataTables UI) opt in.

Fully bounding the *current* UI's load requires wiring the monolith to **server-side DataTables**
(send `page`/`size`/`draw`, render server pages). That UI rework is the **follow-up** that completes
this item; this slice ships the backend capability + the opt-in path with zero UI breakage.

## Design
### Repository (overloaded `findScoped` with `Pageable`)
```java
@Query("select c from Customer c where c.organizationId = :orgId "
     + "or (c.organizationId is null and c.userId = :userId)")
List<Customer> findScoped(@Param("orgId") Long orgId, @Param("userId") Long userId, Pageable pageable);
```
Returning `List` with a `Pageable` applies LIMIT/OFFSET without a count query (kept cheap). Ordering via
the `Pageable`'s sort (or existing `order by ... desc` for Sell).

### Service
Add `findScoped(orgId, userId, Pageable)` delegating to the repo overload.

### Controller (opt-in)
```java
public GenericResponse getUserCustomer(@RequestParam(required=false) Integer page,
                                       @RequestParam(required=false) Integer size, …) {
    List<Customer> objs = (page != null && size != null)
        ? customerService.findScoped(orgId(), userId(), PageRequest.of(page, size))
        : customerService.findScoped(orgId(), userId());
    … map to dtos, same GenericResponse shape …
}
```
Same response shape (dtos in the `object` field) — only the row count differs when paged.

### Scope (this slice)
The high-growth domains: **Customer, Item, Sell** (`getUserSell` already had a "recent N" offset; folded
into proper `page`/`size`). Lookups (ItemType/ItemUnit/Vender/Company) stay unpaged (small) — identical
overload pattern when needed. No DB migration.

## Implement (checklist)
- [x] `CustomerRepo`/`ItemRepo`/`SellRepo` overloaded `findScoped(..., Pageable)`
- [x] services (iface+impl) expose the paged overload
- [x] `getUserCustomer`/`getUserItem`/`getUserSell` accept optional `page`/`size` (backward compatible;
  Sell's legacy `q` offset preserved when page/size absent)
- [ ] build; headed Cypress (no regression — UI sends no page/size so behaviour is unchanged) — **awaiting build**
- [ ] (follow-up, separate slice) monolith server-side DataTables to bound default UI loads

## Test
- Existing specs stay green (no page/size sent → full list, unchanged).
- `GET /getUserSell?page=0&size=5` returns ≤ 5 rows; `page=1` returns the next set.
- Org isolation preserved on the paged path.
