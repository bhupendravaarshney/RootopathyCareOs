import type { IntegrationScreen } from './generated';
import { governedScreenValidator, type AiResponseValidator } from './ai-contracts';

const integrationScreenPattern = /^P13-(0[1-9]|10)$/;
const prohibitedFieldNamePattern =
  /secret|password|token|authorization|api[-_]?key|private[-_]?key|bearer|body|payload|resource[-_]?body/i;

export function integrationScreenValidator(
  organizationId: string,
  screenId: string,
): AiResponseValidator<IntegrationScreen> {
  const validateGovernedScreen = governedScreenValidator<IntegrationScreen>(
    organizationId,
    screenId,
    integrationScreenPattern,
    'm13',
    [
      'connectionId',
      'mappingVersionId',
      'outboundDeliveryId',
      'replayRequestId',
      'apiClientId',
      'fhirExchangeId',
    ],
  );
  return (value): value is IntegrationScreen =>
    validateGovernedScreen(value) &&
    value.rows.every((row) =>
      Object.keys(row.values).every((key) => !prohibitedFieldNamePattern.test(key)),
    ) &&
    value.actions.every((action) =>
      action.fields.every((field) => !prohibitedFieldNamePattern.test(field.key)),
    );
}

export function assertPayloadFreeIntegrationFields(fields: Record<string, string>): void {
  if (Object.keys(fields).some((key) => prohibitedFieldNamePattern.test(key))) {
    throw new Error('Integration action fields must not contain secrets, tokens, or raw payloads.');
  }
}
