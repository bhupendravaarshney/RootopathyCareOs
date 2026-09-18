import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  REVIEW_DRAFT_STATUS,
  validateReviewDraftContent,
  verifyModule1ReviewDrafts,
} from "../verify-module-1-review-drafts.mjs";

const repositoryRoot = fileURLToPath(new URL("../../", import.meta.url));
const checkedManifest = JSON.parse(
  readFileSync(
    new URL("../../contracts/module-1-review-drafts.json", import.meta.url),
    "utf8",
  ),
);

function cloneManifest() {
  return structuredClone(checkedManifest);
}

test("accepts the complete checked owner-review packet without authorizing implementation", () => {
  const result = verifyModule1ReviewDrafts(cloneManifest(), {
    rootDirectory: repositoryRoot,
  });

  assert.equal(result.status, REVIEW_DRAFT_STATUS);
  assert.equal(result.artifactsVerified, 8);
  assert.match(result.reviewPackageSha256, /^[0-9a-f]{64}$/);
  assert.equal(result.implementationAuthorized, false);
});

test("rejects any attempt to label the review packet approved", () => {
  const candidate = cloneManifest();
  candidate.status = "APPROVED";

  assert.throws(
    () =>
      verifyModule1ReviewDrafts(candidate, { rootDirectory: repositoryRoot }),
    /status must be DRAFT_NOT_APPROVED/,
  );
});

test("requires all eight canonical artifacts in their exact order", () => {
  const missing = cloneManifest();
  missing.artifacts.pop();
  assert.throws(
    () => verifyModule1ReviewDrafts(missing, { rootDirectory: repositoryRoot }),
    /exactly 8 artifacts/,
  );

  const reordered = cloneManifest();
  [reordered.artifacts[0], reordered.artifacts[1]] = [
    reordered.artifacts[1],
    reordered.artifacts[0],
  ];
  assert.throws(
    () =>
      verifyModule1ReviewDrafts(reordered, { rootDirectory: repositoryRoot }),
    /kind must be screen-mockups/,
  );
});

test("keeps review drafts outside the production approval directory", () => {
  const candidate = cloneManifest();
  candidate.artifacts[0].path = "approved-inputs/module-1/screen-mockups.md";

  assert.throws(
    () =>
      verifyModule1ReviewDrafts(candidate, { rootDirectory: repositoryRoot }),
    /path must stay in the draft directory/,
  );
});

test("rejects a missing or unreadable draft file", () => {
  const candidate = cloneManifest();
  candidate.artifacts[0].path =
    "docs/module-1-review-drafts/missing-screen-mockups.md";

  assert.throws(
    () =>
      verifyModule1ReviewDrafts(candidate, { rootDirectory: repositoryRoot }),
    /path is missing/,
  );
});

test("requires explicit draft metadata, owner decisions, and acceptance criteria", () => {
  const source = readFileSync(
    new URL(
      "../../docs/module-1-review-drafts/02-design-system.md",
      import.meta.url,
    ),
    "utf8",
  );

  assert.throws(
    () =>
      validateReviewDraftContent(
        "design-system",
        source.replace(REVIEW_DRAFT_STATUS, "APPROVED"),
      ),
    /status must remain DRAFT_NOT_APPROVED/,
  );
  assert.throws(
    () =>
      validateReviewDraftContent(
        "design-system",
        source.replace("## Owner decisions required", "## Decisions"),
      ),
    /required section is missing/,
  );
});

test("requires every M1 screen in the mockup review brief", () => {
  const source = readFileSync(
    new URL(
      "../../docs/module-1-review-drafts/01-screen-mockups.md",
      import.meta.url,
    ),
    "utf8",
  );

  assert.throws(
    () =>
      validateReviewDraftContent(
        "screen-mockups",
        source.replaceAll("M1-23", "M1-XX"),
      ),
    /screen coverage is missing: M1-23/,
  );
});
