import type { CareOsApiClient } from '../../api/client';

export type ReportingClient = Pick<
  CareOsApiClient,
  'getReportingScreen' | 'performReportingAction'
>;
