/**
 * MKT-1a — the pure domain core of the multi-seller marketplace.
 *
 * <p>Every class here is framework-free: no Spring, no JPA, no I/O. They encode the rules of the source design
 * (docs/marketplace-multiseller-source.docx) that do not depend on any open ruling — identity matching,
 * availability arithmetic, the four lifecycle whitelists, acceptance terms, business-day settlement dates,
 * settlement reconciliation, offer eligibility and ranking, and the Phase 1 scope guard — so the persistence and
 * API slices (MKT-1b onward) call one tested implementation instead of re-deriving the rules per endpoint.
 *
 * <p>Design: microservices/docs/marketplace-multiseller-design.md · slice: docs/slices/mkt-1a-domain-core.md.
 */
package com.myplus.marketplace.multiseller.domain;
