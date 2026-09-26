import type { CareOsApiClient } from '../../api/client';

export type SchedulingClient = Pick<
  CareOsApiClient,
  'getSchedulingScreen' | 'performSchedulingAction'
>;
