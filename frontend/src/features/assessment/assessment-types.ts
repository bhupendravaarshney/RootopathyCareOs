import type { CareOsApiClient } from '../../api/client';

export type AssessmentClient = Pick<
  CareOsApiClient,
  'getAssessmentScreen' | 'performAssessmentAction'
>;
