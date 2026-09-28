import type { ReportingScreen } from './generated';
import { governedScreenValidator, type AiResponseValidator } from './ai-contracts';

const reportingScreenPattern = /^P12-(0[1-9]|10)$/;

export function reportingScreenValidator(
  organizationId: string,
  screenId: string,
): AiResponseValidator<ReportingScreen> {
  return governedScreenValidator<ReportingScreen>(
    organizationId,
    screenId,
    reportingScreenPattern,
    'm12',
    ['reportRunId', 'reportScheduleId', 'reportExportId'],
  );
}
