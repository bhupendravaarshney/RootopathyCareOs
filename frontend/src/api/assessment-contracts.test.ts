import type { AssessmentScreen } from './generated';
import { assessmentScreenValidator } from './assessment-contracts';

const organizationId = '0199a2a0-0000-7000-8000-000000000001';
const rowId = '0199a2a0-0000-7000-8000-000000000002';

function assessmentScreen(): AssessmentScreen {
  return {
    actions: [
      {
        fields: [
          {
            inputType: 'textarea',
            key: 'content',
            label: 'Clinical response',
            options: [],
            required: true,
          },
          {
            inputType: 'checkbox',
            key: 'uncertainty',
            label: 'Uncertainty',
            options: [],
            required: false,
          },
        ],
        href: null,
        ifMatchRequired: true,
        key: 'save-section-response',
        label: 'Save response',
        reasonRequired: false,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'primary', label: 'Assessment' }],
    generatedAt: '2026-09-26T08:00:00Z',
    metrics: [{ key: 'active', label: 'Active', tone: 'info', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId,
    pageSize: 25,
    purpose: 'Create append-only assessment documentation.',
    rows: [
      {
        allowedActionKeys: ['save-section-response'],
        assessmentSessionId: rowId,
        encounterId: '0199a2a0-0000-7000-8000-000000000003',
        etag: `"m6:COS-09:${rowId}:3"`,
        id: rowId,
        patientId: '0199a2a0-0000-7000-8000-000000000004',
        revision: 3,
        status: 'draft',
        values: { primary: 'Assessment note' },
      },
    ],
    screenId: 'COS-09',
    title: 'Clinical examination',
  };
}

describe('assessmentScreenValidator', () => {
  it('accepts the exact governed assessment projection', () => {
    expect(assessmentScreenValidator(organizationId, 'COS-09')(assessmentScreen())).toBe(true);
  });

  it('rejects a stale or fabricated strong entity tag', () => {
    const screen = assessmentScreen();
    screen.rows[0]!.etag = `"m6:COS-09:${rowId}:2"`;
    expect(assessmentScreenValidator(organizationId, 'COS-09')(screen)).toBe(false);
  });

  it('rejects unknown clinical fields and action field types', () => {
    const extra = structuredClone(assessmentScreen()) as AssessmentScreen & { rawContent?: string };
    extra.rawContent = 'must never be projected';
    expect(assessmentScreenValidator(organizationId, 'COS-09')(extra)).toBe(false);

    const unsupported = assessmentScreen();
    unsupported.actions[0]!.fields[0]!.inputType = 'html';
    expect(assessmentScreenValidator(organizationId, 'COS-09')(unsupported)).toBe(false);
  });
});
