import type { IntegrationScreen } from './generated';
import { integrationScreenValidator } from './integration-contracts';

const organizationId = '51515151-5151-4151-8151-515151515151';
const connectionId = '52525252-5252-4252-8252-525252525252';

function integrationScreen(): IntegrationScreen {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'validate-connection',
        label: 'Validate configuration',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'providerKind', label: 'Provider kind' }],
    generatedAt: '2026-09-28T15:00:00Z',
    metrics: [{ key: 'validatedConnections', label: 'Validated', tone: 'success', value: 1 }],
    nextCursor: null,
    notices: [
      {
        detail: 'Only digests and governed references are projected.',
        title: 'Payload free',
        tone: 'info',
      },
    ],
    organizationId,
    pageSize: 25,
    purpose: 'Review secret-free connection configuration.',
    rows: [
      {
        allowedActionKeys: ['validate-connection'],
        connectionId,
        etag: `"m13:P13-02:${connectionId}:1"`,
        id: connectionId,
        revision: 1,
        status: 'validated',
        values: { credentialReferenceDigestPrefix: 'a'.repeat(12), providerKind: 'fhir' },
      },
    ],
    screenId: 'P13-02',
    title: 'FHIR endpoints',
  };
}

describe('integrationScreenValidator', () => {
  it('accepts an exact secret-free and payload-free integration projection', () => {
    expect(integrationScreenValidator(organizationId, 'P13-02')(integrationScreen())).toBe(true);
  });

  it('rejects stale revisions and raw payload fields', () => {
    const stale = integrationScreen();
    stale.rows[0]!.etag = `"m13:P13-02:${connectionId}:2"`;
    expect(integrationScreenValidator(organizationId, 'P13-02')(stale)).toBe(false);

    const leaked = integrationScreen();
    leaked.rows[0]!.values.webhookBody = '{"patient":"raw"}';
    expect(integrationScreenValidator(organizationId, 'P13-02')(leaked)).toBe(false);
  });
});
