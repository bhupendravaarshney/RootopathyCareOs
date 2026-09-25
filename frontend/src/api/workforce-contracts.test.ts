import {
  workforceCredentialDocumentAccessValidator,
  workforceEvidenceAccessValidator,
  workforceImpactPreviewValidator,
  workforceScreenValidator,
} from './workforce-contracts';
import type { WorkforceScreen } from './generated';

const organizationId = '22222222-2222-4222-8222-222222222222';
const rowId = '33333333-3333-4333-8333-333333333333';
const memberId = '44444444-4444-4444-8444-444444444444';
const credentialId = '55555555-5555-4555-8555-555555555555';
const documentId = '66666666-6666-4666-8666-666666666666';
const accessIntentId = '77777777-7777-4777-8777-777777777777';
const evidenceId = '88888888-8888-4888-8888-888888888888';

function workforceScreen(): WorkforceScreen {
  return {
    organizationId,
    screenId: 'M2-24',
    title: 'Offboarding',
    purpose: 'Execute a governed workforce lifecycle.',
    generatedAt: '2026-09-25T08:00:00Z',
    metrics: [{ key: 'active', label: 'Active', value: 1, tone: 'info' }],
    columns: [{ key: 'primary', label: 'Member' }],
    rows: [
      {
        id: rowId,
        memberId,
        status: 'active',
        revision: 7,
        etag: `"m2:M2-24:${rowId}:7"`,
        values: { primary: 'Synthetic workforce member' },
        allowedActionKeys: ['request-offboarding'],
      },
    ],
    actions: [
      {
        key: 'request-offboarding',
        label: 'Request offboarding',
        style: 'primary',
        targetRequired: true,
        ifMatchRequired: true,
        reasonRequired: true,
        href: null,
        fields: [
          {
            key: 'accessAction',
            label: 'Access action',
            inputType: 'select',
            required: true,
            help: null,
            options: [{ value: 'revoke_at_effective', label: 'Revoke at effective time' }],
          },
        ],
      },
      {
        key: 'open-timeline',
        label: 'Open timeline',
        style: 'link',
        targetRequired: false,
        ifMatchRequired: false,
        reasonRequired: false,
        href: '#/M2-29',
        fields: [],
      },
    ],
    notices: [],
    nextCursor: null,
    pageSize: 25,
  };
}

describe('workforce runtime contracts', () => {
  it('accepts an exactly bound screen and rejects authority or revision ambiguity', () => {
    const validate = workforceScreenValidator(organizationId, 'M2-24');
    expect(validate(workforceScreen())).toBe(true);

    const missingCursor = workforceScreen();
    Reflect.deleteProperty(missingCursor, 'nextCursor');
    expect(validate(missingCursor)).toBe(false);

    const wrongRevision = workforceScreen();
    wrongRevision.rows[0]!.etag = `"m2:M2-23:${rowId}:7"`;
    expect(validate(wrongRevision)).toBe(false);

    const unknownRowAction = workforceScreen();
    unknownRowAction.rows[0]!.allowedActionKeys = ['approve-offboarding'];
    expect(validate(unknownRowAction)).toBe(false);

    const duplicateRowAction = workforceScreen();
    duplicateRowAction.rows[0]!.allowedActionKeys = ['request-offboarding', 'request-offboarding'];
    expect(validate(duplicateRowAction)).toBe(false);
  });

  it('allows only registered hash navigation and coherent primary-action preconditions', () => {
    const validate = workforceScreenValidator(organizationId, 'M2-24');
    const unsafeLink = workforceScreen();
    unsafeLink.actions[1]!.href = 'javascript:alert(1)';
    expect(validate(unsafeLink)).toBe(false);

    const externalLink = workforceScreen();
    externalLink.actions[1]!.href = 'https://example.test/';
    expect(validate(externalLink)).toBe(false);

    const unboundPrecondition = workforceScreen();
    unboundPrecondition.actions[0]!.targetRequired = false;
    expect(validate(unboundPrecondition)).toBe(false);

    const duplicateField = workforceScreen();
    duplicateField.actions[0]!.fields.push({ ...duplicateField.actions[0]!.fields[0]! });
    expect(validate(duplicateField)).toBe(false);
  });

  it('binds a clean-document access URL to the exact tenant, record, grant, and purpose', () => {
    const response = {
      credentialId,
      documentId,
      accessIntentId,
      readUrl:
        `/api/v1/organizations/${organizationId}/workforce/credentials/${credentialId}` +
        `/documents/${documentId}/accesses/${accessIntentId}?purposeCode=credentialing_review`,
      expiresAt: '2099-09-25T08:10:00Z',
      mediaType: 'application/pdf',
      byteCount: 1024,
      evidenceDigest: 'a'.repeat(64),
      purposeCode: 'credentialing_review',
    };
    const validate = workforceCredentialDocumentAccessValidator(
      organizationId,
      credentialId,
      documentId,
      'credentialing_review',
    );
    expect(validate(response)).toBe(true);
    expect(
      validate({
        ...response,
        readUrl: response.readUrl.replace(organizationId, memberId),
      }),
    ).toBe(false);
    expect(validate({ ...response, purposeCode: 'data_correction' })).toBe(false);
    expect(validate({ ...response, expiresAt: '2020-01-01T00:00:00Z' })).toBe(false);
  });

  it('binds restricted evidence to the requested projection and purpose', () => {
    const response = {
      evidenceId,
      memberId,
      occurredAt: '2026-09-25T08:00:00Z',
      actorId: rowId,
      actorKind: 'service',
      operation: 'm2.offboarding.execute',
      eventName: 'workforce.offboarding.completed',
      schemaVersion: 1,
      subjectType: 'workforce_offboarding_request',
      subjectId: accessIntentId,
      correlationId: 'workforce-evidence-test-001',
      projection: 'member-evidence-detail-v1',
      purposeCode: 'workforce_operations',
      redactionPolicyVersion: 'workforce-evidence-v1',
      payload: { state: 'completed' },
    };
    const validate = workforceEvidenceAccessValidator(
      evidenceId,
      memberId,
      'member-evidence-detail-v1',
      'workforce_operations',
    );
    expect(validate(response)).toBe(true);
    expect(validate({ ...response, projection: 'workforce-audit-detail-v1' })).toBe(false);
    expect(validate({ ...response, purposeCode: 'data_correction' })).toBe(false);
    expect(validate({ ...response, correlationId: 'x'.repeat(129) })).toBe(false);
  });

  it('requires a bounded, internally consistent impact preview', () => {
    const validate = workforceImpactPreviewValidator('M2-24', 'request-offboarding', rowId, 7);
    const response = {
      screenId: 'M2-24',
      actionKey: 'request-offboarding',
      targetId: rowId,
      revision: 7,
      digest: 'b'.repeat(64),
      token: 'c'.repeat(64),
      expiresAt: '2099-09-25T08:10:00Z',
      blocked: false,
      items: [
        {
          code: 'historical_attribution_preserved',
          tone: 'impact',
          detail: 'Historical attribution remains available.',
          affectedCount: 1,
        },
      ],
    };
    expect(validate(response)).toBe(true);
    expect(validate({ ...response, items: [] })).toBe(false);
    expect(validate({ ...response, blocked: true })).toBe(false);
    expect(validate({ ...response, items: Array(33).fill(response.items[0]) })).toBe(false);
    expect(
      validate({
        ...response,
        items: [{ ...response.items[0], code: 'Unstable.Code' }],
      }),
    ).toBe(false);
    expect(
      validate({
        ...response,
        items: [{ ...response.items[0], detail: 'x'.repeat(241) }],
      }),
    ).toBe(false);
  });
});
