import type { SchedulingScreen } from './generated';
import { schedulingScreenValidator } from './scheduling-contracts';

const organizationId = '22222222-2222-4222-8222-222222222222';
const requestId = '33333333-3333-4333-8333-333333333333';

function schedulingScreen(): SchedulingScreen {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'confirm-appointment',
        label: 'Confirm appointment',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
      {
        fields: [],
        href: '#/P4-15',
        ifMatchRequired: false,
        key: 'open-timeline',
        label: 'Open timeline',
        reasonRequired: false,
        style: 'link',
        targetRequired: false,
      },
    ],
    columns: [{ key: 'primary', label: 'Request' }],
    generatedAt: '2026-09-26T08:00:00Z',
    metrics: [{ key: 'held', label: 'Held', tone: 'warning', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId,
    pageSize: 25,
    purpose: 'Confirm an exact held scheduling request.',
    rows: [
      {
        allowedActionKeys: ['confirm-appointment'],
        appointmentId: null,
        etag: `"m4:P4-11:${requestId}:3"`,
        id: requestId,
        patientId: '44444444-4444-4444-8444-444444444444',
        revision: 3,
        status: 'held',
        values: { primary: 'Synthetic appointment request' },
      },
    ],
    screenId: 'P4-11',
    title: 'Confirmation',
  };
}

describe('scheduling runtime contracts', () => {
  it('accepts only exact organization and screen-bound projections', () => {
    const validate = schedulingScreenValidator(organizationId, 'P4-11');
    expect(validate(schedulingScreen())).toBe(true);

    const wrongEtag = schedulingScreen();
    wrongEtag.rows[0]!.etag = `"m4:P4-12:${requestId}:3"`;
    expect(validate(wrongEtag)).toBe(false);

    const unsafeLink = schedulingScreen();
    unsafeLink.actions[1]!.href = 'https://example.test/appointment';
    expect(validate(unsafeLink)).toBe(false);
  });

  it('rejects undeclared row actions and malformed workflow identifiers', () => {
    const validate = schedulingScreenValidator(organizationId, 'P4-11');
    const undeclaredAction = schedulingScreen();
    undeclaredAction.rows[0]!.allowedActionKeys = ['cancel-appointment'];
    expect(validate(undeclaredAction)).toBe(false);

    const malformedPatient = schedulingScreen();
    malformedPatient.rows[0]!.patientId = 'not-a-uuid';
    expect(validate(malformedPatient)).toBe(false);
  });
});
