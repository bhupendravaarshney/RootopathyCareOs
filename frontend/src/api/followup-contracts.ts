import type { FollowupScreen } from './generated';
import { governedScreenValidator, type AiResponseValidator } from './ai-contracts';

const followupScreenPattern = /^P10-0[1-9]$/;

export function followupScreenValidator(
  organizationId: string,
  screenId: string,
): AiResponseValidator<FollowupScreen> {
  return governedScreenValidator<FollowupScreen>(
    organizationId,
    screenId,
    followupScreenPattern,
    'm10',
    [
      'patientId',
      'encounterId',
      'carePlanId',
      'carePlanVersionId',
      'followupPlanId',
      'followupEventId',
      'outcomeDefinitionId',
      'outcomeMeasurementId',
      'escalationEventId',
      'clinicalTaskId',
    ],
  );
}
