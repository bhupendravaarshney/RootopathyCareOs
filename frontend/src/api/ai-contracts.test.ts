import type { AiScreen } from './generated';
import { aiScreenValidator } from './ai-contracts';

const organizationId = '0199a2a0-0000-7000-8000-000000000001';
const sessionId = '0199a2a0-0000-7000-8000-000000000002';

function aiScreen(): AiScreen {
  return {
    actions: [
      {
        fields: [
          {
            inputType: 'select',
            key: 'decision',
            label: 'Decision',
            options: [
              { label: 'Accept', value: 'accepted' },
              { label: 'Reject', value: 'rejected' },
            ],
            required: true,
          },
        ],
        href: null,
        ifMatchRequired: true,
        key: 'decide-output',
        label: 'Record clinician decision',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'draftLabel', label: 'Boundary' }],
    generatedAt: '2026-09-28T08:00:00Z',
    metrics: [{ key: 'draftReady', label: 'Drafts awaiting review', tone: 'warning', value: 1 }],
    nextCursor: null,
    notices: [
      {
        detail: 'No output is accepted automatically.',
        title: 'AI output is a draft',
        tone: 'warning',
      },
    ],
    organizationId,
    pageSize: 25,
    purpose: 'Explicitly accept or reject the latest draft.',
    rows: [
      {
        allowedActionKeys: ['decide-output'],
        encounterId: '0199a2a0-0000-7000-8000-000000000004',
        etag: `"m8:P8-09:${sessionId}:4"`,
        id: sessionId,
        outputId: '0199a2a0-0000-7000-8000-000000000005',
        outputVersionId: '0199a2a0-0000-7000-8000-000000000006',
        patientId: '0199a2a0-0000-7000-8000-000000000003',
        revision: 4,
        status: 'draft_ready',
        values: { draftLabel: 'AI-generated draft — clinician review required' },
      },
    ],
    screenId: 'P8-09',
    title: 'Clinician review and approval',
  };
}

describe('aiScreenValidator', () => {
  it('accepts an exact governed AI draft projection', () => {
    expect(aiScreenValidator(organizationId, 'P8-09')(aiScreen())).toBe(true);
  });

  it('rejects stale revisions and provider-only leakage', () => {
    const stale = aiScreen();
    stale.rows[0]!.etag = `"m8:P8-09:${sessionId}:3"`;
    expect(aiScreenValidator(organizationId, 'P8-09')(stale)).toBe(false);

    const leaked = structuredClone(aiScreen()) as AiScreen & { providerPayload?: string };
    leaked.providerPayload = 'raw context';
    expect(aiScreenValidator(organizationId, 'P8-09')(leaked)).toBe(false);
  });
});
