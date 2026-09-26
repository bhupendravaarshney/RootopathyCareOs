import type { CareOsApiClient } from '../../api/client';

export type PatientRegistryClient = Pick<
  CareOsApiClient,
  'getPatientRegistryScreen' | 'performPatientRegistryAction' | 'previewPatientRegistryImpact'
>;
