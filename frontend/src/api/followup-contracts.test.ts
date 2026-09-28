import type { FollowupScreen } from './generated';
import { followupScreenValidator } from './followup-contracts';

const organizationId = '0199a3a0-0000-7000-8000-000000000001';
const escalationId = '0199a3a0-0000-7000-8000-000000000002';

function followupScreen(): FollowupScreen {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'acknowledge-escalation',
        label: 'Acknowledge escalation',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'severity', label: 'Severity' }],
    generatedAt: '2026-09-28T08:00:00Z',
    metrics: [{ key: 'open', label: 'Open escalations', tone: 'warning', value: 1 }],
    nextCursor: null,
    notices: [
      {
        detail: 'Every breach creates owned work and acknowledgement evidence.',
        title: 'Owned escalation',
        tone: 'danger',
      },
    ],
    organizationId,
    pageSize: 25,
    purpose: 'Review owned threshold breaches.',
    rows: [
      {
        allowedActionKeys: ['acknowledge-escalation'],
        clinicalTaskId: '0199a3a0-0000-7000-8000-000000000003',
        escalationEventId: escalationId,
        etag: `"m10:P10-05:${escalationId}:0"`,
        followupPlanId: '0199a3a0-0000-7000-8000-000000000004',
        id: escalationId,
        outcomeMeasurementId: '0199a3a0-0000-7000-8000-000000000005',
        patientId: '0199a3a0-0000-7000-8000-000000000006',
        revision: 0,
        status: 'open',
        values: { severity: 'critical' },
      },
    ],
    screenId: 'P10-05',
    title: 'Escalation',
  };
}

describe('followupScreenValidator', () => {
  it('accepts an exact governed follow-up projection', () => {
    expect(followupScreenValidator(organizationId, 'P10-05')(followupScreen())).toBe(true);
  });

  it('rejects stale revisions and undeclared clinical narrative', () => {
    const stale = followupScreen();
    stale.rows[0]!.etag = `"m10:P10-05:${escalationId}:1"`;
    expect(followupScreenValidator(organizationId, 'P10-05')(stale)).toBe(false);

    const leaked = structuredClone(followupScreen()) as FollowupScreen & {
      patientNarrative?: string;
    };
    leaked.patientNarrative = 'undeclared sensitive payload';
    expect(followupScreenValidator(organizationId, 'P10-05')(leaked)).toBe(false);
  });
});
