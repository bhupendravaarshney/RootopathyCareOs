import type { CareOsApiClient } from '../../api/client';

export type DocumentClient = Pick<
  CareOsApiClient,
  'getDocumentScreen' | 'performDocumentAction' | 'uploadDocument' | 'createDocumentAccess'
>;
