import type { CareOsApiClient } from '../../api/client';

export type EncounterClient = Pick<
  CareOsApiClient,
  'getEncounterScreen' | 'performEncounterAction'
>;
