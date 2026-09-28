import type { BillingScreen } from './generated';
import { governedScreenValidator, type AiResponseValidator } from './ai-contracts';

const billingScreenPattern = /^P11-(0[1-9]|1[01])$/;

export function billingScreenValidator(
  organizationId: string,
  screenId: string,
): AiResponseValidator<BillingScreen> {
  return governedScreenValidator<BillingScreen>(
    organizationId,
    screenId,
    billingScreenPattern,
    'm11',
    [
      'patientId',
      'appointmentId',
      'encounterId',
      'priceBookId',
      'packageId',
      'estimateId',
      'invoiceId',
      'paymentIntentId',
      'paymentId',
      'refundId',
      'adjustmentId',
      'claimId',
      'remittanceId',
      'reconciliationId',
      'financialExportId',
    ],
  );
}
