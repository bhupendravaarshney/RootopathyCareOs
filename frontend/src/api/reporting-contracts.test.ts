import type { ReportingScreen } from './generated';
import { reportingScreenValidator } from './reporting-contracts';

const organizationId = '0199b3c0-0000-7000-8000-000000000001';
const reportRunId = '0199b3c0-0000-7000-8000-000000000002';

function reportingScreen(): ReportingScreen {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'request-report-export',
        label: 'Request export',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'reportFamily', label: 'Report family' }],
    generatedAt: '2026-09-28T14:00:00Z',
    metrics: [{ key: 'completedRuns', label: 'Completed runs', tone: 'success', value: 1 }],
    nextCursor: null,
    notices: [
      {
        detail: 'Source clinical and identity records are excluded.',
        title: 'Minimum necessary',
        tone: 'info',
      },
    ],
    organizationId,
    pageSize: 25,
    purpose: 'Request an exact aggregate report export.',
    rows: [
      {
        allowedActionKeys: ['request-report-export'],
        etag: `"m12:P12-09:${reportRunId}:1"`,
        id: reportRunId,
        reportRunId,
        revision: 1,
        status: 'completed',
        values: { artifact: 'report_run', reportFamily: 'operational' },
      },
    ],
    screenId: 'P12-09',
    title: 'Scheduled exports',
  };
}

describe('reportingScreenValidator', () => {
  it('accepts an exact aggregate-only reporting projection', () => {
    expect(reportingScreenValidator(organizationId, 'P12-09')(reportingScreen())).toBe(true);
  });

  it('rejects stale revisions and source-record identifiers', () => {
    const stale = reportingScreen();
    stale.rows[0]!.etag = `"m12:P12-09:${reportRunId}:2"`;
    expect(reportingScreenValidator(organizationId, 'P12-09')(stale)).toBe(false);

    const leaked = structuredClone(reportingScreen()) as ReportingScreen & { patientId?: string };
    leaked.patientId = '0199b3c0-0000-7000-8000-000000000099';
    expect(reportingScreenValidator(organizationId, 'P12-09')(leaked)).toBe(false);
  });
});
