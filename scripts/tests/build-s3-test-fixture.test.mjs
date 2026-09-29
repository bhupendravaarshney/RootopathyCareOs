import assert from "node:assert/strict";
import test from "node:test";

import {
  FIXTURE_MANIFEST_DIGEST,
  verifyFixtureMetadata,
} from "../build-s3-test-fixture.mjs";

test("accepts only the approved S3 fixture manifest digest", () => {
  assert.equal(
    verifyFixtureMetadata({
      "containerimage.digest": FIXTURE_MANIFEST_DIGEST,
    }),
    FIXTURE_MANIFEST_DIGEST,
  );
});

test("rejects missing or changed S3 fixture digest evidence", () => {
  assert.throws(() => verifyFixtureMetadata({}), /received no digest/);
  assert.throws(
    () =>
      verifyFixtureMetadata({
        "containerimage.digest": `sha256:${"0".repeat(64)}`,
      }),
    /digest mismatch/,
  );
});
