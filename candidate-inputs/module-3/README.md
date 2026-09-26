# Module 3 candidate input package

This directory is the first reviewable implementation-input candidate for CareOS Module 3 Patient Registry. It is derived from the exact product definition accepted for mockup preparation under `M3-PRODUCT-ACCEPTANCE-20260926-01` and the CareOS Java + React/Node build specification.

It is **not approved for implementation**:

- every artifact is marked `CANDIDATE_FOR_APPROVAL`;
- the directory is `candidate-inputs/module-3/`, not `approved-inputs/module-3/`;
- the candidate verifier always reports `implementationAuthorized: false`;
- a successful integrity check proves completeness, provenance and checksum stability—not legal, privacy, clinical-safety, security, interoperability, product or implementation approval;
- no production migration, table, permission, registry entry, API, worker, notification, export, FHIR endpoint, generated client or live P3 route may be created until an accountable reviewer separately accepts the exact candidate digest.

## Contents

| Kind                                       | Candidate artifact                      |
| ------------------------------------------ | --------------------------------------- |
| Screen mockups                             | `01-screen-mockups.html`                |
| Design System extension                    | `02-design-system.md`                   |
| Data dictionary and validation             | `03-data-dictionary-and-validation.md`  |
| Lifecycle and transition matrix            | `04-lifecycle-and-transition-matrix.md` |
| Authorization policy                       | `05-authorization-policy.md`            |
| Audit and event registry                   | `06-audit-and-event-registry.md`        |
| Readiness and activation policy            | `07-readiness-and-activation-policy.md` |
| History, export, FHIR and retention policy | `08-history-and-export-policy.md`       |

Open `01-screen-mockups.html` directly in a browser. It is a self-contained, responsive, keyboard-operable prototype for P3-01 through P3-16. It contains conspicuously synthetic data, performs no network/storage/authentication/business action and cannot approve itself.

After every artifact is complete, run:

```bash
node scripts/verify-module-3-candidate-inputs.mjs
node --test scripts/tests/verify-module-3-candidate-inputs.test.mjs
```

The verifier emits the exact package digest. Reviewers must record `ACCEPT`, `ACCEPT_WITH_CHANGES` or `REJECT` against that digest. Any changed byte creates a different package digest. Product-definition acceptance, repository access, a passing verifier, mockup review or a direction to continue is not production implementation approval.

The current `m3-candidate-1` digest is `c383cd7dbe927d2443958f71350c8cd59ac1a8eb725dc5b2a70e40ced115e21f`.

## Review authorities

The final candidate decision should include product/design, health-information management, privacy/legal for the intended deployment, clinical safety, security/identity, accessibility, operations and interoperability review. A missing local catalogue keeps only its dependent capability unavailable; it never produces a permissive fallback.
