import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  calculatePackageSha256,
  MODULE_1_SCREENS,
  REQUIRED_ARTIFACT_KINDS,
  requireApprovedModule1Inputs,
  verifyModule1InputGate,
} from "../verify-module-1-inputs.mjs";

const source = JSON.parse(
  readFileSync(
    new URL("../../contracts/module-1-input-gate.json", import.meta.url),
    "utf8",
  ),
);
const schema = JSON.parse(
  readFileSync(
    new URL("../../contracts/module-1-input-gate.schema.json", import.meta.url),
    "utf8",
  ),
);
const NOW = new Date("2026-09-16T16:00:00Z");

function blockedGate() {
  const gate = structuredClone(source);
  gate.status = "BLOCKED_INPUT";
  gate.artifacts = [];
  gate.approval = null;
  return gate;
}

function changed(mutator) {
  const gate = blockedGate();
  mutator(gate);
  return gate;
}

function withWorkspace(callback) {
  const root = mkdtempSync(join(tmpdir(), "careos-module-1-inputs-"));
  try {
    return callback(root);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

function addArtifact(root, kind, version = "2026.09.16") {
  const relative = `approved-inputs/module-1/${kind}.json`;
  const absolute = join(root, ...relative.split("/"));
  const content = JSON.stringify({ kind, version });
  mkdirSync(join(root, "approved-inputs", "module-1"), { recursive: true });
  writeFileSync(absolute, content, "utf8");
  return {
    kind,
    version,
    path: relative,
    sha256: createHash("sha256").update(content, "utf8").digest("hex"),
  };
}

function approvedGate(root) {
  const gate = blockedGate();
  gate.status = "APPROVED";
  gate.artifacts = REQUIRED_ARTIFACT_KINDS.map((kind) =>
    addArtifact(root, kind),
  );
  const evidencePath = "approved-inputs/module-1/approval-record.json";
  const evidence = JSON.stringify({
    recordId: "M1-APPROVAL-2026-09-16",
    decision: "APPROVED",
    packageSha256: calculatePackageSha256(gate.artifacts),
  });
  writeFileSync(join(root, ...evidencePath.split("/")), evidence, "utf8");
  gate.approval = {
    decision: "APPROVED",
    recordId: "M1-APPROVAL-2026-09-16",
    approvedBy: "CareOS Product Authority",
    approvedAt: "2026-09-16T10:00:00Z",
    screens: [...MODULE_1_SCREENS],
    packageSha256: calculatePackageSha256(gate.artifacts),
    evidencePath,
    evidenceSha256: createHash("sha256").update(evidence, "utf8").digest("hex"),
  };
  return gate;
}

test("accepts the checked checksum-bound approved repository state", () => {
  const result = verifyModule1InputGate(source, { now: NOW });
  assert.equal(result.status, "APPROVED");
  assert.equal(result.artifactsPresent, 8);
  assert.equal(result.artifactsRequired, 8);
  assert.deepEqual(result.missingArtifacts, []);
  assert.equal(result.approval, "APPROVED");
  assert.equal(result.implementationAuthorized, true);
  assert.equal(requireApprovedModule1Inputs(result), result);
});

test("keeps the published schema aligned with the executable catalogue", () => {
  assert.deepEqual(
    schema.properties.requiredArtifacts.prefixItems.map((item) => item.const),
    REQUIRED_ARTIFACT_KINDS,
  );
  assert.deepEqual(
    schema.$defs.approval.properties.screens.prefixItems.map(
      (item) => item.const,
    ),
    MODULE_1_SCREENS,
  );
  assert.deepEqual(schema.properties.status.enum, [
    "BLOCKED_INPUT",
    "APPROVED",
  ]);
  assert.ok(schema.$defs.approval.required.includes("evidencePath"));
  assert.ok(schema.$defs.approval.required.includes("evidenceSha256"));
});

test("accepts and verifies a partial package while keeping it blocked", () => {
  withWorkspace((root) => {
    const gate = changed((candidate) => {
      candidate.artifacts.push(addArtifact(root, "screen-mockups"));
    });
    const result = verifyModule1InputGate(gate, {
      rootDirectory: root,
      now: NOW,
    });
    assert.equal(result.artifactsPresent, 1);
    assert.equal(result.missingArtifacts.length, 7);
    assert.equal(result.packageSha256, null);
    assert.equal(result.implementationAuthorized, false);
  });
});

test("rejects drift in the canonical required-artifact catalogue", () => {
  const gate = changed((candidate) => {
    candidate.requiredArtifacts.pop();
  });
  assert.throws(
    () => verifyModule1InputGate(gate, { now: NOW }),
    /canonical eight artifact kinds/,
  );
});

test("rejects duplicate artifact kinds", () => {
  withWorkspace((root) => {
    const first = addArtifact(root, "screen-mockups", "one");
    const path = "approved-inputs/module-1/screen-mockups-copy.json";
    const absolute = join(root, ...path.split("/"));
    const content = JSON.stringify({ kind: "screen-mockups", version: "two" });
    writeFileSync(absolute, content, "utf8");
    const second = {
      kind: "screen-mockups",
      version: "two",
      path,
      sha256: createHash("sha256").update(content, "utf8").digest("hex"),
    };
    const gate = changed((candidate) => {
      candidate.artifacts = [first, second];
    });
    assert.throws(
      () => verifyModule1InputGate(gate, { rootDirectory: root, now: NOW }),
      /Duplicate artifact kind/,
    );
  });
});

test("rejects artifact paths outside the approved input directory", () => {
  const gate = changed((candidate) => {
    candidate.artifacts = [
      {
        kind: "screen-mockups",
        version: "one",
        path: "../mockups.zip",
        sha256: "a".repeat(64),
      },
    ];
  });
  assert.throws(
    () => verifyModule1InputGate(gate, { now: NOW }),
    /must stay under approved-inputs\/module-1/,
  );
});

test("rejects a missing artifact file", () => {
  withWorkspace((root) => {
    const gate = changed((candidate) => {
      candidate.artifacts = [
        {
          kind: "screen-mockups",
          version: "one",
          path: "approved-inputs/module-1/missing.zip",
          sha256: "a".repeat(64),
        },
      ];
    });
    assert.throws(
      () => verifyModule1InputGate(gate, { rootDirectory: root, now: NOW }),
      /path does not exist/,
    );
  });
});

test("rejects an artifact checksum mismatch", () => {
  withWorkspace((root) => {
    const artifact = addArtifact(root, "screen-mockups");
    artifact.sha256 = "0".repeat(64);
    const gate = changed((candidate) => {
      candidate.artifacts = [artifact];
    });
    assert.throws(
      () => verifyModule1InputGate(gate, { rootDirectory: root, now: NOW }),
      /sha256 does not match/,
    );
  });
});

test("rejects an approved claim with an incomplete artifact package", () => {
  withWorkspace((root) => {
    const gate = changed((candidate) => {
      candidate.status = "APPROVED";
      candidate.artifacts = [addArtifact(root, "screen-mockups")];
      candidate.approval = {};
    });
    assert.throws(
      () => verifyModule1InputGate(gate, { rootDirectory: root, now: NOW }),
      /APPROVED is missing required artifacts/,
    );
  });
});

test("rejects an approval that does not cover all 23 screens", () => {
  withWorkspace((root) => {
    const gate = approvedGate(root);
    gate.approval.screens.pop();
    assert.throws(
      () => verifyModule1InputGate(gate, { rootDirectory: root, now: NOW }),
      /M1-01 through M1-23/,
    );
  });
});

test("rejects approval bound to different package or evidence content", () => {
  withWorkspace((root) => {
    const gate = approvedGate(root);
    gate.approval.packageSha256 = "0".repeat(64);
    assert.throws(
      () => verifyModule1InputGate(gate, { rootDirectory: root, now: NOW }),
      /does not bind the exact artifact package/,
    );

    const evidenceDrift = approvedGate(root);
    evidenceDrift.approval.evidenceSha256 = "0".repeat(64);
    assert.throws(
      () =>
        verifyModule1InputGate(evidenceDrift, {
          rootDirectory: root,
          now: NOW,
        }),
      /approval\.evidence\.sha256 does not match/,
    );

    const reusedArtifact = approvedGate(root);
    reusedArtifact.approval.evidencePath = reusedArtifact.artifacts[0].path;
    reusedArtifact.approval.evidenceSha256 = reusedArtifact.artifacts[0].sha256;
    assert.throws(
      () =>
        verifyModule1InputGate(reusedArtifact, {
          rootDirectory: root,
          now: NOW,
        }),
      /approval evidence must be distinct/,
    );
  });
});

test("rejects placeholder, invalid, and future approval evidence", () => {
  withWorkspace((root) => {
    const placeholder = approvedGate(root);
    placeholder.approval.approvedBy = "TBD placeholder";
    assert.throws(
      () =>
        verifyModule1InputGate(placeholder, {
          rootDirectory: root,
          now: NOW,
        }),
      /non-placeholder approver/,
    );

    const invalid = approvedGate(root);
    invalid.approval.approvedAt = "2026-02-31T10:00:00Z";
    assert.throws(
      () =>
        verifyModule1InputGate(invalid, {
          rootDirectory: root,
          now: NOW,
        }),
      /must be a real timestamp/,
    );

    const future = approvedGate(root);
    future.approval.approvedAt = "2026-09-17T10:00:00Z";
    assert.throws(
      () => verifyModule1InputGate(future, { rootDirectory: root, now: NOW }),
      /must not be in the future/,
    );
  });
});

test("accepts a complete checksum-bound approved package", () => {
  withWorkspace((root) => {
    const result = verifyModule1InputGate(approvedGate(root), {
      rootDirectory: root,
      now: NOW,
    });
    assert.equal(result.status, "APPROVED");
    assert.equal(result.artifactsPresent, 8);
    assert.deepEqual(result.missingArtifacts, []);
    assert.match(result.packageSha256, /^[0-9a-f]{64}$/);
    assert.equal(result.approval, "APPROVED");
    assert.equal(result.implementationAuthorized, true);
    assert.equal(requireApprovedModule1Inputs(result), result);
  });
});

test("the production-mode assertion still rejects a valid blocked state", () => {
  const result = verifyModule1InputGate(blockedGate(), { now: NOW });
  assert.throws(
    () => requireApprovedModule1Inputs(result),
    /remains BLOCKED_INPUT/,
  );
});
