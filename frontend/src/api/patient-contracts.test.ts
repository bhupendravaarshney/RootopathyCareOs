import {
  patientRegistryImpactPreviewValidator,
  patientRegistryScreenValidator,
} from './patient-contracts';
import type { PatientRegistryScreen } from './generated';

const organizationId = '22222222-2222-4222-8222-222222222222';
const patientId = '33333333-3333-4333-8333-333333333333';

function patientScreen(): PatientRegistryScreen {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'correct-identity',
        label: 'Correct identity',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
      {
        fields: [],
        href: '#/P3-16',
        ifMatchRequired: false,
        key: 'open-timeline',
        label: 'Open timeline',
        reasonRequired: false,
        style: 'link',
        targetRequired: false,
      },
    ],
    columns: [{ key: 'primary', label: 'Patient' }],
    generatedAt: '2026-09-26T08:00:00Z',
    metrics: [{ key: 'active', label: 'Active', tone: 'info', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId,
    pageSize: 25,
    purpose: 'Maintain governed patient identity.',
    rows: [
      {
        allowedActionKeys: ['correct-identity'],
        etag: `"m3:P3-05:${patientId}:4"`,
        id: patientId,
        patientId,
        revision: 4,
        status: 'active',
        values: { primary: 'Synthetic patient' },
      },
    ],
    screenId: 'P3-05',
    title: 'Identity and demographics',
  };
}

describe('patient registry runtime contracts', () => {
  it('accepts only exact, actor-bound screen projections', () => {
    const validate = patientRegistryScreenValidator(organizationId, 'P3-05');
    expect(validate(patientScreen())).toBe(true);

    const wrongEtag = patientScreen();
    wrongEtag.rows[0]!.etag = `"m3:P3-06:${patientId}:4"`;
    expect(validate(wrongEtag)).toBe(false);

    const unknownAction = patientScreen();
    unknownAction.rows[0]!.allowedActionKeys = ['merge-patient'];
    expect(validate(unknownAction)).toBe(false);

    const unsafeLink = patientScreen();
    unsafeLink.actions[1]!.href = 'https://example.test/patient';
    expect(validate(unsafeLink)).toBe(false);
  });

  it('requires a fresh, coherent patient impact preview', () => {
    const validate = patientRegistryImpactPreviewValidator(
      'P3-15',
      'execute-patient-merge',
      patientId,
      4,
    );
    const response = {
      actionKey: 'execute-patient-merge',
      blocked: false,
      digest: 'a'.repeat(64),
      expiresAt: '2099-09-26T08:10:00Z',
      items: [
        {
          affectedCount: 2,
          code: 'contacts_preserved',
          detail: 'Contact lineage remains attached to the source record.',
          tone: 'impact',
        },
      ],
      revision: 4,
      screenId: 'P3-15',
      targetId: patientId,
      token: 'b'.repeat(64),
    };
    expect(validate(response)).toBe(true);
    expect(validate({ ...response, targetId: organizationId })).toBe(false);
    expect(validate({ ...response, blocked: true })).toBe(false);
    expect(validate({ ...response, expiresAt: '2020-01-01T00:00:00Z' })).toBe(false);
  });
});
