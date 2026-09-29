import type { DocumentAccessResponse, DocumentScreen } from './generated';
import { documentAccessResponseValidator, documentScreenValidator } from './document-contracts';

const organizationId = '0199a2a0-0000-7000-8000-000000000001';
const documentId = '0199a2a0-0000-7000-8000-000000000002';
const versionId = '0199a2a0-0000-7000-8000-000000000003';
const accessIntentId = '0199a2a0-0000-7000-8000-000000000004';

function documentScreen(): DocumentScreen {
  return {
    actions: [
      {
        fields: [
          {
            inputType: 'file',
            key: 'file',
            label: 'Document file',
            options: [],
            required: true,
          },
        ],
        href: null,
        ifMatchRequired: true,
        key: 'classify-document',
        label: 'Classify document',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'primary', label: 'Document' }],
    generatedAt: '2026-09-28T08:00:00Z',
    metrics: [{ key: 'clean', label: 'Clean', tone: 'success', value: 1 }],
    nextCursor: null,
    notices: [],
    organizationId,
    pageSize: 25,
    purpose: 'Review immutable document versions.',
    rows: [
      {
        allowedActionKeys: ['classify-document'],
        documentId,
        documentVersionId: versionId,
        etag: `"m7:P7-04:${documentId}:2"`,
        id: documentId,
        patientId: '0199a2a0-0000-7000-8000-000000000005',
        revision: 2,
        status: 'clean',
        values: { $kind: 'document', primary: 'Laboratory report' },
      },
    ],
    screenId: 'P7-04',
    title: 'Classification and metadata',
  };
}

function accessResponse(): DocumentAccessResponse {
  return {
    accessIntentId,
    accessPath: `/api/v1/organizations/${organizationId}/documents/${documentId}/versions/${versionId}/accesses/${accessIntentId}?purposeKey=result_review`,
    byteCount: 1024,
    documentId,
    documentVersionId: versionId,
    expiresAt: '2026-09-28T08:01:00Z',
    mediaType: 'application/pdf',
    purposeKey: 'result_review',
    sha256: 'a'.repeat(64),
  };
}

describe('documentScreenValidator', () => {
  it('accepts the exact governed document projection including a file field', () => {
    expect(documentScreenValidator(organizationId, 'P7-04')(documentScreen())).toBe(true);
  });

  it('rejects stale revisions and unregistered fields', () => {
    const stale = documentScreen();
    stale.rows[0]!.etag = `"m7:P7-04:${documentId}:1"`;
    expect(documentScreenValidator(organizationId, 'P7-04')(stale)).toBe(false);

    const extra = structuredClone(documentScreen()) as DocumentScreen & { providerUrl?: string };
    extra.providerUrl = 'https://storage.example.test/bearer';
    expect(documentScreenValidator(organizationId, 'P7-04')(extra)).toBe(false);
  });
});

describe('documentAccessResponseValidator', () => {
  it('accepts only the exact relative actor-bound access path', () => {
    const validate = documentAccessResponseValidator(
      organizationId,
      documentId,
      versionId,
      'result_review',
    );
    expect(validate(accessResponse())).toBe(true);

    const leaked = accessResponse();
    leaked.accessPath = 'https://storage.example.test/bearer-token';
    expect(validate(leaked)).toBe(false);
  });
});
