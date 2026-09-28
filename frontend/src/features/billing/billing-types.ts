import type { CareOsApiClient } from '../../api/client';

export type BillingClient = Pick<CareOsApiClient, 'getBillingScreen' | 'performBillingAction'>;
