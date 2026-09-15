import { fileURLToPath } from 'node:url';

export const checkedApiDirectory = fileURLToPath(new URL('../src/api/generated/', import.meta.url));

const contractPath = fileURLToPath(
  new URL('../../contracts/openapi/careos-foundation.json', import.meta.url),
);

export function apiGeneratorConfig(outputPath = checkedApiDirectory) {
  return {
    input: contractPath,
    output: {
      clean: true,
      entryFile: true,
      path: outputPath,
      postProcess: ['prettier'],
    },
    plugins: [
      {
        comments: true,
        enums: false,
        name: '@hey-api/typescript',
      },
    ],
  };
}
