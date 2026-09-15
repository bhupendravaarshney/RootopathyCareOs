import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';

import { verifyFrontendBoundaries } from '../verify-frontend-boundaries.mjs';

async function fixture(files) {
  const root = await mkdtemp(join(tmpdir(), 'careos-frontend-boundaries-'));
  await Promise.all(
    Object.entries(files).map(async ([path, contents]) => {
      const target = join(root, path);
      await mkdir(join(target, '..'), { recursive: true });
      await writeFile(target, contents, 'utf8');
    }),
  );
  return root;
}

async function withFixture(files, assertion) {
  const root = await fixture(files);
  try {
    await assertion(root);
  } finally {
    await rm(root, { force: true, recursive: true });
  }
}

test('accepts inward feature, page, component, data, and API imports', async () => {
  await withFixture(
    {
      'api/client.ts': "export const client = 'client';",
      'components/Button.tsx':
        "import { label } from '../data/labels'; export const Button = () => label;",
      'data/labels.ts': "export const label = 'Continue';",
      'features/session/View.tsx':
        "import { client } from '../../api/client'; import { Button } from '../../components/Button'; export const View = () => Button() + client;",
      'pages/Home.tsx':
        "import { View } from '../features/session/View'; export const Home = View;",
    },
    async (root) => {
      const result = await verifyFrontendBoundaries(root);
      assert.deepEqual(result.features, ['session']);
      assert.equal(result.status, 'PASS');
    },
  );
});

test('rejects a shared component importing an application feature', async () => {
  await withFixture(
    {
      'components/Shell.tsx':
        "import { session } from '../features/session/state'; export { session };",
      'features/session/state.ts': 'export const session = {};',
    },
    async (root) => {
      await assert.rejects(
        verifyFrontendBoundaries(root),
        /components\/Shell\.tsx \(components\).*reverses the frontend dependency direction/,
      );
    },
  );
});

test('rejects direct cross-feature imports', async () => {
  await withFixture(
    {
      'features/records/state.ts': 'export const records = {};',
      'features/session/state.ts':
        "import { records } from '../records/state'; export const session = records;",
    },
    async (root) => {
      await assert.rejects(
        verifyFrontendBoundaries(root),
        /imports another feature \(records\) directly/,
      );
    },
  );
});

test('rejects API code importing a UI page or escaping src', async () => {
  await withFixture(
    {
      'api/client.ts':
        "export { Page } from '../pages/Page'; export { secret } from '../../outside';",
      'pages/Page.tsx': 'export const Page = () => null;',
    },
    async (root) => {
      await assert.rejects(
        verifyFrontendBoundaries(root),
        /api\/client\.ts \(api\).*reverses the frontend dependency direction.*api\/client\.ts imports \.\.\/\.\.\/outside, which escapes/s,
      );
    },
  );
});
