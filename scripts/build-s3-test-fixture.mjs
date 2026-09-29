import { mkdirSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { spawnSync } from "node:child_process";

export const FIXTURE_IMAGE =
  "careos-s3-test-fixture:minio-release-2025-09-07";
export const FIXTURE_PLATFORM = "linux/amd64";
export const FIXTURE_SOURCE_EPOCH = "1757261589";
export const FIXTURE_MANIFEST_DIGEST =
  "sha256:bb6f358423eec8c666f70d24dbab12a0b9467b5071f2bb30ee64767d3dce82d1";

const repositoryRoot = resolve(fileURLToPath(new URL("..", import.meta.url)));
const metadataPath = resolve(repositoryRoot, "build", "s3-fixture-metadata.json");

function run(command, args) {
  const result = spawnSync(command, args, {
    cwd: repositoryRoot,
    env: process.env,
    shell: false,
    stdio: "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} exited ${result.status}`);
  }
}

export function verifyFixtureMetadata(metadata) {
  const actual = metadata?.["containerimage.digest"];
  if (actual !== FIXTURE_MANIFEST_DIGEST) {
    throw new Error(
      `S3 fixture digest mismatch: expected ${FIXTURE_MANIFEST_DIGEST}, received ${actual ?? "no digest"}`,
    );
  }
  return actual;
}

export function buildAndVerifyFixture() {
  mkdirSync(dirname(metadataPath), { recursive: true });
  run("docker", [
    "buildx",
    "build",
    "--load",
    "--pull",
    "--provenance=false",
    "--platform",
    FIXTURE_PLATFORM,
    "--build-arg",
    `SOURCE_DATE_EPOCH=${FIXTURE_SOURCE_EPOCH}`,
    "--metadata-file",
    metadataPath,
    "--tag",
    FIXTURE_IMAGE,
    "./test-fixtures/s3",
  ]);
  const digest = verifyFixtureMetadata(
    JSON.parse(readFileSync(metadataPath, "utf8")),
  );
  run("docker", ["run", "--rm", FIXTURE_IMAGE, "--version"]);
  process.stdout.write(
    `CareOS S3 compatibility fixture verified: ${FIXTURE_IMAGE} (${digest})\n`,
  );
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  buildAndVerifyFixture();
}
