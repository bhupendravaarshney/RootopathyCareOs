import type { CarePlanScreen } from './generated';
import { carePlanScreenValidator } from './care-plan-contracts';

const organizationId = '0199a2a0-0000-7000-8000-000000000001';
const carePlanId = '0199a2a0-0000-7000-8000-000000000002';

function carePlanScreen(): CarePlanScreen {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'submit-plan',
        label: 'Submit for review',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'planTitle', label: 'Plan' }],
    generatedAt: '2026-09-28T08:00:00Z',
    metrics: [{ key: 'draft', label: 'Draft plans', tone: 'warning', value: 1 }],
    nextCursor: null,
    notices: [
      {
        detail: 'Approval requires complete ownership, consent and safety evidence.',
        title: 'Exact-version review',
        tone: 'warning',
      },
    ],
    organizationId,
    pageSize: 25,
    purpose: 'Approve an exact complete care-plan version.',
    rows: [
      {
        allowedActionKeys: ['submit-plan'],
        carePlanVersionId: '0199a2a0-0000-7000-8000-000000000003',
        encounterId: '0199a2a0-0000-7000-8000-000000000004',
        etag: `"m9:P9-10:${carePlanId}:4"`,
        id: carePlanId,
        patientId: '0199a2a0-0000-7000-8000-000000000005',
        revision: 4,
        status: 'draft',
        values: { planTitle: 'Coordinated recovery plan' },
      },
    ],
    screenId: 'P9-10',
    title: 'Clinician approval',
  };
}

describe('carePlanScreenValidator', () => {
  it('accepts an exact governed care-plan projection', () => {
    expect(carePlanScreenValidator(organizationId, 'P9-10')(carePlanScreen())).toBe(true);
  });

  it('rejects stale revisions and undeclared clinical narrative', () => {
    const stale = carePlanScreen();
    stale.rows[0]!.etag = `"m9:P9-10:${carePlanId}:3"`;
    expect(carePlanScreenValidator(organizationId, 'P9-10')(stale)).toBe(false);

    const leaked = structuredClone(carePlanScreen()) as CarePlanScreen & {
      interactionNarrative?: string;
    };
    leaked.interactionNarrative = 'undeclared sensitive payload';
    expect(carePlanScreenValidator(organizationId, 'P9-10')(leaked)).toBe(false);
  });
});
