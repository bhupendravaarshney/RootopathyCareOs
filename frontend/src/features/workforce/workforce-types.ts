import type { CareOsApiClient } from '../../api/client';

export type WorkforceClient = Pick<
  CareOsApiClient,
  | 'getWorkforceScreen'
  | 'performWorkforceAction'
  | 'accessWorkforceExport'
  | 'uploadWorkforceCredentialDocument'
>;
