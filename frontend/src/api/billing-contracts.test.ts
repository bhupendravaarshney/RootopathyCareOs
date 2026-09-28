import type { BillingScreen } from './generated';
import { billingScreenValidator } from './billing-contracts';

const organizationId = '0199b3b0-0000-7000-8000-000000000001';
const invoiceId = '0199b3b0-0000-7000-8000-000000000002';

function billingScreen(): BillingScreen {
  return {
    actions: [
      {
        fields: [],
        href: null,
        ifMatchRequired: true,
        key: 'record-payment',
        label: 'Record payment',
        reasonRequired: true,
        style: 'primary',
        targetRequired: true,
      },
    ],
    columns: [{ key: 'balanceMinor', label: 'Balance' }],
    generatedAt: '2026-09-28T13:00:00Z',
    metrics: [{ key: 'openInvoices', label: 'Open invoices', tone: 'warning', value: 1 }],
    nextCursor: null,
    notices: [
      {
        detail: 'Financial state never changes clinical record completion.',
        title: 'Clinical independence',
        tone: 'info',
      },
    ],
    organizationId,
    pageSize: 25,
    purpose: 'Record exact settlement evidence.',
    rows: [
      {
        allowedActionKeys: ['record-payment'],
        etag: `"m11:P11-06:${invoiceId}:2"`,
        id: invoiceId,
        invoiceId,
        patientId: '0199b3b0-0000-7000-8000-000000000003',
        revision: 2,
        status: 'partially_paid',
        values: { balanceMinor: '18600', currency: 'INR' },
      },
    ],
    screenId: 'P11-06',
    title: 'Payment',
  };
}

describe('billingScreenValidator', () => {
  it('accepts an exact governed billing projection', () => {
    expect(billingScreenValidator(organizationId, 'P11-06')(billingScreen())).toBe(true);
  });

  it('rejects stale revisions and undeclared card data', () => {
    const stale = billingScreen();
    stale.rows[0]!.etag = `"m11:P11-06:${invoiceId}:3"`;
    expect(billingScreenValidator(organizationId, 'P11-06')(stale)).toBe(false);

    const leaked = structuredClone(billingScreen()) as BillingScreen & { cardNumber?: string };
    leaked.cardNumber = '4111111111111111';
    expect(billingScreenValidator(organizationId, 'P11-06')(leaked)).toBe(false);
  });
});
