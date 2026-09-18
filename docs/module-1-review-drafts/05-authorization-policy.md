# Module 1 authorization policy review brief

**Artifact kind:** `authorization-policy`  
**Status:** `DRAFT_NOT_APPROVED`  
**Draft version:** `review-draft-1`  
**Approval authority:** Owner decision required

## Proposed review baseline

The existing `careos-phase0-reference-v1` registry is an executable least-privilege proposal only. It defines organization owner, administrator, member, and local bootstrap roles; profile/membership/invitation/audit/MFA permissions; delegation edges; operation risks; hidden or explicit denial mode; reason and recent authentication requirements; maker-checker controls; and final-owner protection. Production preflight rejects activation of this reference registry.

### Existing reference entries requiring accept/replace decisions

| Area               | Reference behavior to review                                                                                                                                                                         |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Roles              | `organization_owner` is final-owner and not invitation-assignable; `organization_administrator` and `organization_member` are invitation-assignable; `local_bootstrap` is never production eligible. |
| Delegation         | Owner may assign administrator/member; administrator may assign member; neither can assign an owner under the reference policy.                                                                      |
| Profile            | Members can read; owners/administrators can manage the bounded reference profile. Profile update requires a reason but not recent authentication or independent approval.                            |
| Invitations        | Owner/administrator can issue or revoke only an explicitly delegable role; both operations require reason and recent authentication within 600 seconds.                                              |
| Membership         | Owner-only reference management uses reason, recent authentication, maker-checker, and database final-owner safeguards.                                                                              |
| Audit              | Owner/administrator can read minimum-necessary audit evidence after recent authentication; missing access uses hidden-resource behavior.                                                             |
| MFA administration | Owner-only reference reset requires reason, recent authentication within 300 seconds, maker-checker separation, target separation, and original-maker execution.                                     |
| Readiness          | Uses profile-read permission and hidden denial in the provisional reference slice.                                                                                                                   |

### Production registry fields required

Every permission needs a stable key, name, description, scope, risk class, sensitivity, status/version, retirement/compatibility rule, and owning team. Every role needs a stable key, description, interactive/non-interactive flag, invitation eligibility, final-owner behavior, exact grants, scope bounds, and lifecycle. Every operation needs:

- one permission and purpose;
- read/mutation classification and denial mode (`hidden` or `explicit`);
- reason, recent authentication, MFA, maximum age/skew, and maker-checker rules;
- actor/subject separation, delegation ceiling, final-owner, and organization-lifecycle guards;
- idempotency, concurrency, audit/outbox mapping, and route/use-case ownership;
- stable behavior for unknown, retired, disabled, or version-mismatched policy.

### Candidate Module 1 permission families for review

These families organize decisions; their keys and grants are not accepted:

| Family                | Candidate capabilities requiring explicit grants                                                                                                          |
| --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Organization identity | Profile read/manage; identifiers read/manage/verify; addresses/contacts read/manage; international settings read/manage; governance contacts read/manage. |
| Network               | Facilities read/manage/lifecycle; departments/units read/manage/lifecycle; locations read/manage/lifecycle; operating-hours read/manage.                  |
| Services and schemes  | Service catalogue read/manage/lifecycle; service assignment read/manage/lifecycle; identifier scheme read/manage/activate/retire.                         |
| Access                | Membership read; invitation issue/revoke/resend decision; role/scope change; membership suspend/revoke; MFA administrative reset; final-owner transfer.   |
| Activation            | Readiness read; validation run; configuration submit; independent approve/reject; activate; suspend/rollback decision.                                    |
| Evidence              | Configuration history read/compare; audit read/detail; purpose-bound export request/access; approval evidence read.                                       |

### Role/grant worksheet

Owners must decide whether the three reference interactive roles are sufficient or whether bounded roles such as security, privacy, clinical-governance, network configuration, service configuration, auditor, or export approver are required. Avoid broad role names without an exact grant matrix. UI action hiding is a convenience projection of server policy, never authorization evidence.

## Owner decisions required

1. Approve or replace all stable role, permission, and operation keys and exact grants; name the owner of each registry family.
2. Approve invitation eligibility, delegation ceilings, scope types, final-owner transfer/revocation, and account/membership lifecycle rules.
3. Approve risk class, hidden versus explicit denial, reason, recent-authentication age, MFA, maker-checker, actor/subject separation, and approval expiry for every operation.
4. Decide organization MFA enforcement and self-disable policy separately from administrator reset.
5. Approve non-interactive identities, purposes, credential provisioning/rotation, tenant discovery, and exact worker operations before any worker starts.
6. Approve registry versioning, retirement, backwards compatibility, emergency access, break-glass evidence, and policy rollout/rollback.

## Acceptance checklist

- [ ] Every M1 route/action maps to one stable operation and permission with an exact denial mode and purpose.
- [ ] Role grants and delegation form an intentional least-privilege matrix with no implicit wildcard or role-name inference.
- [ ] Final-owner, maker-checker, recent authentication, MFA, reason, scope, and subject-separation decisions are complete.
- [ ] Database, HTTP, direct-SQL, cross-tenant, stale-membership, delegation, and policy-retirement attack cases are specified.
- [ ] Product, identity, security, privacy, governance, operations, and engineering owners record review outcomes.
- [ ] A named authority records approval evidence for the exact checksum-bound bundle.
