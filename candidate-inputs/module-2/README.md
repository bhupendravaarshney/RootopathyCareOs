# Module 2 candidate input package

This directory is the first reviewable implementation-input candidate for CareOS Module 2. It is bound to the user-accepted Module 1 baseline commit `2ba6c9b3b567d0371c8523e6f18945ec33138ae4` and to the CareOS Java + React/Node build specification whose SHA-256 is `2c8f9f020c7c1a21678c795df57fd8be1b659f2141ab7c8747b5dec973db0bfe`.

It is **not approved**:

- every artifact is marked `CANDIDATE_FOR_APPROVAL`;
- the directory is `candidate-inputs/module-2/`, not `approved-inputs/module-2/`;
- the candidate verifier always reports `implementationAuthorized: false`;
- a successful integrity check proves completeness and checksum stability, not product, clinical-governance, privacy, security, or implementation approval;
- no migration, endpoint, worker, permission, or production screen may be implemented from this package until an accountable reviewer accepts its exact digest.

## Contents

| Kind | Candidate artifact |
| --- | --- |
| Screen mockups | `01-screen-mockups.html` |
| Design System extension | `02-design-system.md` |
| Data dictionary and validation | `03-data-dictionary-and-validation.md` |
| Lifecycle and transition matrix | `04-lifecycle-and-transition-matrix.md` |
| Authorization policy | `05-authorization-policy.md` |
| Audit and event registry | `06-audit-and-event-registry.md` |
| Readiness and activation policy | `07-readiness-and-activation-policy.md` |
| History and export policy | `08-history-and-export-policy.md` |

Open `01-screen-mockups.html` directly in a browser. It is a self-contained, responsive, keyboard-operable review prototype for M2-01 through M2-29. It uses synthetic labels only and performs no network, storage, authentication, upload, invitation, approval, or business mutation.

After every artifact is complete, run:

```bash
node scripts/verify-module-2-candidate-inputs.mjs
node --test scripts/tests/verify-module-2-candidate-inputs.test.mjs
```

The first command emits the exact package digest. Reviewers must record `ACCEPT`, `ACCEPT_WITH_CHANGES`, or `REJECT` against that digest. Any changed byte creates a new candidate version or digest. Silence, repository access, a successful verifier, acceptance of Module 1, or a direction to continue is not approval of the unseen Module 2 package.

## Review authorities

The final decision should include product/design, clinical governance, credentialing/HR, privacy/records, security, accessibility, and operations review. Jurisdiction-specific extensions may tighten retention, working-age, registration, supervision, or notification rules, but may not weaken tenant isolation, maker/checker separation, evidence integrity, fail-closed document scanning, or minimum-necessary access.
