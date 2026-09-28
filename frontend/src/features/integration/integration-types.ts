import type { CareOsApiClient } from '../../api/client';

export type IntegrationClient = Pick<
  CareOsApiClient,
  'getIntegrationScreen' | 'performIntegrationAction'
>;
