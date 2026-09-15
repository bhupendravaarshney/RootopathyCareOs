import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { createClient } from '@hey-api/openapi-ts';

import { apiGeneratorConfig, checkedApiDirectory } from './api-codegen-config.mjs';

async function listFiles(root, directory = root) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];

  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await listFiles(root, path)));
    } else if (entry.isFile()) {
      files.push(relative(root, path));
    }
  }

  return files.sort();
}

function normalized(contents) {
  return contents.replaceAll('\r\n', '\n');
}

const frontendDirectory = fileURLToPath(new URL('../', import.meta.url));
const temporaryDirectory = await mkdtemp(join(frontendDirectory, '.careos-api-types-'));

try {
  await createClient(apiGeneratorConfig(temporaryDirectory));

  const expectedFiles = await listFiles(temporaryDirectory);
  const checkedFiles = await listFiles(checkedApiDirectory).catch(() => []);

  if (JSON.stringify(checkedFiles) !== JSON.stringify(expectedFiles)) {
    throw new Error(
      `Generated API file set is stale. Expected ${expectedFiles.join(', ') || 'no files'}; found ${checkedFiles.join(', ') || 'no files'}. Run npm run api:generate.`,
    );
  }

  for (const file of expectedFiles) {
    const [expected, checked] = await Promise.all([
      readFile(join(temporaryDirectory, file), 'utf8'),
      readFile(join(checkedApiDirectory, file), 'utf8'),
    ]);

    if (normalized(checked) !== normalized(expected)) {
      throw new Error(`Generated API type drift detected in ${file}. Run npm run api:generate.`);
    }
  }

  console.log(
    JSON.stringify(
      {
        files: expectedFiles,
        source: '../contracts/openapi/careos-foundation.json',
        status: 'PASS',
      },
      null,
      2,
    ),
  );
} finally {
  await rm(temporaryDirectory, { force: true, recursive: true });
}
