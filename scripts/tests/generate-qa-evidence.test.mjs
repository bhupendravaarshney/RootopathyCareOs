import assert from "node:assert/strict";
import test from "node:test";

import {
  countPlaywrightList,
  countVitestList,
  repositoryReleaseGate,
  summarizeSurefireXmlTexts,
} from "../generate-qa-evidence.mjs";

test("counts discovered Vitest and five-project Playwright cases", () => {
  assert.equal(countVitestList([{ name: "one" }, { name: "two" }]), 2);
  assert.equal(
    countPlaywrightList({
      suites: [
        {
          specs: [
            { tests: [{ projectName: "desktop" }, { projectName: "mobile" }] },
          ],
          suites: [{ specs: [{ tests: [{ projectName: "tablet" }] }] }],
        },
      ],
    }),
    3,
  );
});

test("does not turn local or security-incomplete evidence into release approval", () => {
  assert.equal(
    repositoryReleaseGate({
      origin: "local-working-tree",
      qualityPassed: true,
      securityStatus: "PASS",
      treeDirty: false,
    }),
    "NOT_EVALUATED",
  );
  assert.equal(
    repositoryReleaseGate({
      origin: "hosted-ci",
      qualityPassed: true,
      securityStatus: "NOT_EVALUATED",
      treeDirty: false,
    }),
    "NOT_EVALUATED",
  );
  assert.equal(
    repositoryReleaseGate({
      origin: "hosted-ci",
      qualityPassed: true,
      securityStatus: "PASS",
      treeDirty: true,
    }),
    "NOT_EVALUATED",
  );
  assert.equal(
    repositoryReleaseGate({
      origin: "hosted-ci",
      qualityPassed: true,
      securityStatus: "PASS",
      treeDirty: false,
    }),
    "PASS",
  );
  assert.equal(
    repositoryReleaseGate({
      origin: "hosted-ci",
      qualityPassed: false,
      securityStatus: "PASS",
      treeDirty: false,
    }),
    "FAIL",
  );
});

test("sums Surefire evidence without erasing failures or skips", () => {
  assert.deepEqual(
    summarizeSurefireXmlTexts([
      '<testsuite tests="4" errors="1" skipped="2" failures="1">',
      '<testsuite failures="0" tests="3" skipped="0" errors="0">',
    ]),
    { errors: 1, failures: 1, skipped: 2, tests: 7 },
  );
  assert.throws(
    () => summarizeSurefireXmlTexts(['<testsuite tests="1">']),
    /omits errors/,
  );
});
