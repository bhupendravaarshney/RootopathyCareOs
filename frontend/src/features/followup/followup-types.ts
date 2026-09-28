import type { CareOsApiClient } from '../../api/client';

export type FollowupClient = Pick<CareOsApiClient, 'getFollowupScreen' | 'performFollowupAction'>;
