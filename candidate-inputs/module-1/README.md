# Module 1 candidate input package

This directory converts `review-draft-1` into one internally consistent implementation candidate. It freezes working choices so product, design, security, privacy, accessibility, operations, and engineering reviewers can evaluate the same bounded package.

It is **not approved**:

- every artifact is marked `CANDIDATE_FOR_APPROVAL`;
- the directory is `candidate-inputs/module-1/`, not `approved-inputs/module-1/`;
- the candidate verifier always reports `implementationAuthorized: false`;
- `contracts/module-1-input-gate.json` remains `BLOCKED_INPUT` with zero production artifacts;
- no candidate file may be copied into the production manifest until an accountable authority accepts the exact content and checksum.

## Contents

| Kind                            | Candidate artifact                      |
| ------------------------------- | --------------------------------------- |
| Screen mockups                  | `01-screen-mockups.html`                |
| Design System                   | `02-design-system.md`                   |
| Data dictionary and validation  | `03-data-dictionary-and-validation.md`  |
| Lifecycle and transition matrix | `04-lifecycle-and-transition-matrix.md` |
| Authorization policy            | `05-authorization-policy.md`            |
| Audit and event registry        | `06-audit-and-event-registry.md`        |
| Readiness and activation policy | `07-readiness-and-activation-policy.md` |
| History and export policy       | `08-history-and-export-policy.md`       |

Open `01-screen-mockups.html` directly in a browser. It is a self-contained, responsive, keyboard-operable review prototype for M1-01 through M1-23 with state switching and no network, storage, authentication, or business mutation.

Run:

```bash
node scripts/verify-module-1-candidate-inputs.mjs
```

The resulting digest identifies this exact candidate package for review. Approval requires final files below `approved-inputs/module-1/`, a distinct approval-evidence file, and a passing production command:

```bash
node scripts/verify-module-1-inputs.mjs --require-approved
```

## Review disposition

Reviewers must record one disposition for every artifact: `ACCEPT`, `ACCEPT_WITH_CHANGES`, or `REJECT`. Any change creates a new candidate version and digest. Silence, a successful candidate check, repository access, or implementation activity is never approval.
