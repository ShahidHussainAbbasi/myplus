# MS — ModelMapper → MapStruct, one record type at a time

**Status:** MS-1..5 BUILT (2026-10-04; user chose "all of MS-1..5"). MS-6 (remove ModelMapper) not started. Follows MM-1 (mapper profiles), which made today's behaviour explicit
and characterized it. That is exactly what a safe migration needs first.

## 1. Why (R&D)
| | ModelMapper (today) | MapStruct |
|---|---|---|
| When mapping is decided | Runtime, by name-matching heuristics (STANDARD/STRICT) | Compile time: generated plain Java |
| A field that silently stops mapping | Possible, and found only by a person (MM-1 §4) | **Build fails** with `unmappedTargetPolicy = ERROR` |
| Speed | Reflection | Plain getters/setters (commonly cited as 10–100× faster) |
| Debugging | Inside the library | Generated source you can read in `target/generated-sources` |
| Industry use | Declining for new code | Default in Spring ecosystems (JHipster, most Spring Boot templates) |

**Versions:** MapStruct 1.6.3 with `lombok-mapstruct-binding` 0.2.0. Processor order: Lombok, then MapStruct, then the
binding. All must be in `maven-compiler-plugin` `annotationProcessorPaths`, beside Lombok's existing entry.

## 2. Rule 0: scope, counted
business-service has 34 `map()` calls across 4 profiles (MM-1) and 15 type pairs. The other services hold ~37 plain maps
(agriculture 8, analytics 3, appointment 16 with typemaps, campaign 6, welfare 4).
**This programme covers business-service only.** The others stay on ModelMapper until their own slice.

## 3. Method: the strangler, per type pair
```mermaid
flowchart LR
  A[pair still on ModelMapper] -->|write MapStruct mapper, ERROR policy| B[characterization test: MapStruct output == MM-1 profile output on MapperProfilesTest fixtures]
  B -->|green| C[switch the call sites of that pair]
  C --> D[Cypress screens of that pair]
  D --> E[next pair]
```
- Each pair has its own mapper interface (`StoreMapper`, `VenderMapper`…). The date formats come from one shared
  `DateFormats` helper that holds the same patterns `AppUtil` uses today.
- The **oracle is the MM-1 profile.** MapStruct must produce field-for-field the same output on the fixtures, or the
  difference is listed and decided field by field. A field is never left to fall out quietly.
- `unmappedTargetPolicy = ERROR` forces every target field to be mapped or explicitly `ignore`d, with a comment saying why.

## 4. Order (lowest risk first)
| Slice | Pair(s) | Profile | Why this order |
|---|---|---|---|
| MS-1 (pilot) | Store → StoreDTO | plain | 4 calls, small, proves the toolchain and the oracle |
| MS-2 | Company, ItemType, ItemUnit, Vender (both ways) | plain | Simple, no dates |
| MS-3 | Customer ↔ DTO, Purchase → DTO | display | The date converters |
| MS-4 | Sell, CustomerHistory, Customer (sale screens) | saleDisplay STRICT | Money screens; heaviest Cypress regression |
| MS-5 | PurchaseDTO → Purchase | purchaseInput | **Writes money/stock**: last, with the trial balance asserted |
| MS-6 | Remove ModelMapper from business-service | — | Only when no call remains |

## 5. Tests per slice
- **Unit:** the characterization test against the MM-1 profile, plus the build itself, which fails on any unmapped field.
- **Cypress:** the specs that render that pair (the MM-1 regression list), plus `mm-1-mapper-dates.cy.js`.
- **Test Book:** a short section per slice: nothing on those screens should look different.

## 6. Results and findings (2026-10-04)
- **34 of 34** business-service `map()` calls now use MapStruct. Nine mappers: Store, Company, ItemType, ItemUnit, Vender,
  Customer, Purchase (display), SaleScreen (Sell + CustomerHistory + Customer, STRICT oracle) and PurchaseInput.
- `MapStructOracleTest` runs every pair against its MM-1 profile. Each comparison runs on a full fixture AND an
  all-empty one, and fails if the oracle itself threw. A blindness check proves one dropped field fails it.
- business-service 436/0/0/0. Cypress 151/151 across 18 specs (sale, purchase, returns, reports, stores, vendors,
  payables, expense bills, idempotency, mm-1 gate).
- **Left on ModelMapper:** `ObjectMapperUtils` (static STRICT, one call: CustomerDTO → CustomerHistory on the sale
  write path). It moves in MS-6, which also deletes MapperProfiles once its oracle role ends.

### MS-F1: an implicit field, now explicit (behaviour kept)
ModelMapper silently copied `Vender.payableAdvance` into `VenderDTO.advance` (STANDARD token match). `getUserVender`
overwrites it by payables source (the finance figure, or 0 on BUSINESS). **`getAllVender` does not**, so it shows the
stored advance even on BUSINESS, against the DTO's own "0 on BUSINESS" rule. Its only reader is `vender.cy.js`.
**Decision needed:** apply the same payables-source rule there, or leave it.

### MS-F2: null dates display as "now" (behaviour kept, decision needed)
ModelMapper invoked AppUtil's converters for null sources, and those answer today / now. So a Customer or Purchase with
no `dated`/`updated` shows the **current time**, which changes on every refresh. The empty-source oracle run found it.
`DisplayDates` reproduces it explicitly. **Decision needed:** show blank instead (truthful), after checking that each
screen renders a blank date.
