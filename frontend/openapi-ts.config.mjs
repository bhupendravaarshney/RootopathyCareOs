import { defineConfig } from '@hey-api/openapi-ts';

import { apiGeneratorConfig } from './scripts/api-codegen-config.mjs';

export default defineConfig(apiGeneratorConfig());
