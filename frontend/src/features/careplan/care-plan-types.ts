import type { CareOsApiClient } from '../../api/client';

export type CarePlanClient = Pick<CareOsApiClient, 'getCarePlanScreen' | 'performCarePlanAction'>;
