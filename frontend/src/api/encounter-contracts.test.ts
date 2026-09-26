import type { EncounterScreen } from './generated';
import { encounterScreenValidator } from './encounter-contracts';

const organizationId = '0199a2a0-0000-7000-8000-000000000001';
const rowId = '0199a2a0-0000-7000-8000-000000000002';

function encounterScreen(): EncounterScreen {
  return {
    actions: [
      {
        fields: [
          {
            inputType: 'textarea',
            key: 'content',
            label: 'Clinical note',
            options: [],
            required: true,
          },
          {
            inputType: 'checkbox',
            key: 'lateEntry',
            label: 'Late entry',
            options: [],
            required: false,
          },
        ],
        href: null,
        ifMatchRequired: true,
        key: 'save-note-version',
        label: 'Save note version',
        reasonRequired: false,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'primary', label: 'Encounter' }],
    generatedAt: '2026-09-26T08:00:00Z',
    metrics: [{ key: 'active', label: 'Active', tone: 'info', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId,
    pageSize: 25,
    purpose: 'Create append-only encounter documentation.',
    rows: [
      {
        allowedActionKeys: ['save-note-version'],
        appointmentId: null,
        encounterId: rowId,
        episodeId: '0199a2a0-0000-7000-8000-000000000003',
        etag: `"m5:P5-09:${rowId}:3"`,
        id: rowId,
        patientId: '0199a2a0-0000-7000-8000-000000000004',
        revision: 3,
        status: 'draft',
        values: { primary: 'Encounter note' },
      },
    ],
    screenId: 'P5-09',
    title: 'Encounter notes',
  };
}

describe('encounterScreenValidator', () => {
  it('accepts the exact governed encounter projection', () => {
    expect(encounterScreenValidator(organizationId, 'P5-09')(encounterScreen())).toBe(true);
  });

  it('rejects a stale or fabricated strong entity tag', () => {
    const screen = encounterScreen();
    screen.rows[0]!.etag = `"m5:P5-09:${rowId}:2"`;
    expect(encounterScreenValidator(organizationId, 'P5-09')(screen)).toBe(false);
  });

  it('rejects unknown clinical fields and action field types', () => {
    const extra = structuredClone(encounterScreen()) as EncounterScreen & { rawContent?: string };
    extra.rawContent = 'must never be projected';
    expect(encounterScreenValidator(organizationId, 'P5-09')(extra)).toBe(false);

    const unsupported = encounterScreen();
    unsupported.actions[0]!.fields[0]!.inputType = 'html';
    expect(encounterScreenValidator(organizationId, 'P5-09')(unsupported)).toBe(false);
  });
});
