# Module 1 owner-review drafts

These eight documents convert the empty Module 1 approval checklist into a concrete review packet. They are grounded in the checked 46-page build specification, the M1-01 through M1-23 traceability ledger, and the current reference implementation.

They are deliberately **not approval evidence**:

- every artifact is marked `DRAFT_NOT_APPROVED`;
- every file stays under `docs/module-1-review-drafts/`, never `approved-inputs/module-1/`;
- unresolved product and policy choices remain visibly assigned to an owner;
- `contracts/module-1-input-gate.json` remains `BLOCKED_INPUT` with no artifacts or approval;
- `verify-module-1-inputs.mjs --require-approved` must continue to fail until real approved files and evidence exist.

## Packet contents

| Required production bundle      | Review draft                            |
| ------------------------------- | --------------------------------------- |
| Screen mockups                  | `01-screen-mockups.md`                  |
| Design System                   | `02-design-system.md`                   |
| Data dictionary and validation  | `03-data-dictionary-and-validation.md`  |
| Lifecycle and transition matrix | `04-lifecycle-and-transition-matrix.md` |
| Authorization policy            | `05-authorization-policy.md`            |
| Audit and event registry        | `06-audit-and-event-registry.md`        |
| Readiness and activation policy | `07-readiness-and-activation-policy.md` |
| History and export policy       | `08-history-and-export-policy.md`       |

## Owner handoff workflow

1. Assign a named accountable owner and reviewers for each artifact.
2. Resolve every item under “Owner decisions required”; replace candidate values rather than silently accepting them.
3. Produce final native design/source files and policy documents with bounded versions.
4. Record formal approval outside these drafts, including the authority, decision time, scope, and evidence.
5. Copy only the final approved bundles below `approved-inputs/module-1/`, calculate lowercase SHA-256 values, and list them in `contracts/module-1-input-gate.json`.
6. Run the normal input verifier to obtain its canonical package digest.
7. Bind that digest, all M1-01 through M1-23 screens, and a distinct approval-evidence file in the manifest.
8. Run `node scripts/verify-module-1-inputs.mjs --require-approved`. Production implementation may start only when it passes.

Run `node scripts/verify-module-1-review-drafts.mjs` to check this draft packet. Its output always reports `implementationAuthorized: false`.
