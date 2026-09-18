# Module 1 screen mockup review brief

**Artifact kind:** `screen-mockups`  
**Status:** `DRAFT_NOT_APPROVED`  
**Draft version:** `review-draft-1`  
**Approval authority:** Owner decision required

## Proposed review baseline

The final bundle should contain native, versioned desktop and responsive mockups for all 23 screens at 1440, 1024, 768, 390, and 320 pixel widths. Every state must identify the API operation, permission, user action, focus destination, responsive behavior, and recovery path it represents. Prototype screenshots are reference evidence only and must not be relabelled as final designs.

Every protected screen needs explicit loading, empty, no-result, validation-error, hidden-resource, explicit-denial, stale/conflict, dependency-failure, success, and session-expired treatment where the state is applicable. Destructive or high-risk actions need reason capture, recent-authentication, confirmation, maker-checker, and completion evidence states as required by the approved policy.

| Screen | Mockup set and interaction decisions required                                                                                                                                                     |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| M1-01  | Login, throttled/generic failure, password recovery entry, safe post-login destination, expired-session notice, keyboard order, and narrow layout.                                                |
| M1-02  | Invitation issue/list/detail/revoke, one-use acceptance for new and existing accounts, expired/used/revoked token, role and expiry selection, delivery status, and safe generic failures.         |
| M1-03  | MFA challenge, enrollment setup, one-time recovery codes, recent authentication, code replacement, organization enforcement state, self-disable decision, and maker-checker administrative reset. |
| M1-04  | Loading, zero/one/multiple authorized organizations, lifecycle-ineligible choices, explicit selection, context switch, failure/retry, and no-access state.                                        |
| M1-05  | Dashboard metric definitions, readiness summary, exceptions, freshness, lifecycle status, empty/dependency failure, drill-down destinations, and permission-hidden cards.                         |
| M1-06  | Ordered setup gates, blocker/warning/complete states, source evidence, freshness, deep links, retry, override visibility, and activation dependency.                                              |
| M1-07  | Profile read/edit, field sensitivity, validation, reason, ETag conflict/reload, successful revision evidence, denied state, and correction/history entry.                                         |
| M1-08  | Organization identifier list/add/edit/supersede, authority and jurisdiction selection, duplicate/conflict, evidence, effective dates, and immutable history.                                      |
| M1-09  | Address/contact list and editor, type, primary designation, effective dating, validation, sensitivity, overlap/conflict, supersession, and history.                                               |
| M1-10  | Country/timezone/locale/date/number settings, defaulting, impact preview, effective-time decision, conflict, and confirmation.                                                                    |
| M1-11  | Clinical/privacy/security/billing governance contacts, linked-person/account selection, required-role gaps, escalation details, visibility, and replacement history.                              |
| M1-12  | Server list, approved columns, filters, sort, cursor pagination, empty/no-result, lifecycle badges, one add action, narrow card alternative, and permission-hidden action.                        |
| M1-13  | Draft facility wizard steps, save/resume, hierarchy/address details, validation summary, cancel/discard, conflict, readiness preview, and activation/closure constraints.                         |
| M1-14  | Department/unit hierarchy tree and list alternatives, type/depth, parent change, effective dates, impacted descendants, closure, cycle/conflict, and history.                                     |
| M1-15  | Physical/virtual location list/editor, facility and parent context, address/virtual details, capacity, lifecycle, hierarchy constraints, and impact review.                                       |
| M1-16  | Weekly hours batch, overnight intervals, timezone/DST explanation, holiday exceptions, overlap validation, atomic save, conflict, and downstream impact.                                          |
| M1-17  | Service catalogue list/editor, coding, clinical owner, lifecycle, duplicate validation, retirement impact, and permission states.                                                                 |
| M1-18  | Facility/location service assignment, eligibility, effective dates, capacity/availability, conflicting assignment, suspend/end, and impact review.                                                |
| M1-19  | Identifier scheme and immutable version editor, prefix/pattern/sequence preview, scope, concurrency, activation, supersession, and retirement impact.                                             |
| M1-20  | Administrator membership/invitation list, scope and role, delegation ceiling, final-owner safeguard, change/revoke request, independent decision, MFA reset, and evidence.                        |
| M1-21  | Typed validation run, grouped blocker/warning results, freshness, evidence links, submit, independent approve/reject, stale rerun, activation success, and rollback policy display.               |
| M1-22  | Configuration version list, filters, cursor page, compare selector/diff, actor/reason/effective metadata, export-purpose dialog, export status, and retained artifact state.                      |
| M1-23  | Minimum-necessary audit list/detail, operation/event/actor/time filters, redaction, correlation navigation, cursor page, purpose/recent-auth export, and unavailable/expired export.              |

### Shared interaction annotations

- Name the exact control labels and accessible descriptions; do not rely on color, position, or icon alone.
- Specify initial focus, error-summary focus, dialog focus trap/return, drawer behavior, skip navigation, and keyboard-only completion.
- Define table-to-card transformations and action placement at each required width; horizontal scrolling alone needs an explicit reviewed exception.
- Identify server-authoritative values, freshness timestamps, optimistic revisions, and whether an action is hidden, disabled with a reason, or explicitly denied.
- Prohibit real information in any non-production mockup or test fixture.

## Owner decisions required

1. Approve the product route hierarchy, safe redirect rules, workspace information architecture, and back-navigation behavior.
2. Approve final copy, labels, help text, date/time/number presentation, iconography, and clinical/governance terminology.
3. Approve the state applicability matrix per screen, including hidden versus explicit denial and empty versus no-result treatment.
4. Approve responsive table/card alternatives and the exact component behavior at all five reference widths.
5. Approve every confirmation, reason, recent-authentication, maker-checker, stale-write, retry, and export interaction.
6. Supply the protected original source assets and native design-file identifiers; static exports alone are insufficient for implementation handoff.

## Acceptance checklist

- [ ] Native design source and immutable review export identify the same bounded version.
- [ ] M1-01 through M1-23 each include desktop and responsive layouts plus all applicable states.
- [ ] Every action maps to one approved operation, permission, event set, API result, and recovery path.
- [ ] Keyboard order, focus transitions, accessible names/descriptions, contrast, zoom/reflow, and reduced-motion behavior are annotated.
- [ ] Product, security, privacy, accessibility, operations, and engineering owners record review outcomes.
- [ ] A named authority records approval evidence for the exact checksum-bound bundle.
