import type { CareOsApiClient } from '../../api/client';

export type AiClient = Pick<CareOsApiClient, 'getAiScreen' | 'performAiAction'>;
