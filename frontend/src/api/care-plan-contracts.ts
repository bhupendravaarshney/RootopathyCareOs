import type { CarePlanScreen } from './generated';
import { governedScreenValidator, type AiResponseValidator } from './ai-contracts';

const carePlanScreenPattern = /^P9-(0[1-9]|1[0-2])$/;

export function carePlanScreenValidator(
  organizationId: string,
  screenId: string,
): AiResponseValidator<CarePlanScreen> {
  return governedScreenValidator<CarePlanScreen>(
    organizationId,
    screenId,
    carePlanScreenPattern,
    'm9',
    ['patientId', 'encounterId', 'carePlanVersionId', 'interventionId', 'clinicalTaskId'],
  );
}
