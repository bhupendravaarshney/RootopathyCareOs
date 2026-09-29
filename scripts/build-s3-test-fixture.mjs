import { mkdirSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { spawnSync } from "node:child_process";

export const FIXTURE_IMAGE = "careos-s3-test-fixture:minio-release-2025-09-07";
export const FIXTURE_PLATFORM = "linux/amd64";
export const FIXTURE_SOURCE_EPOCH = "1757261589";
export const FIXTURE_BUILDKIT_IMAGE =
  "moby/buildkit@sha256:040d34121c27906c4ff9ac152a30d52bf2c5d328d3bb748916bb3d2743c02528";
export const FIXTURE_MANIFEST_DIGEST =
  "sha256:9b225075e9847bde86fa19f8274353ae7c5c4018af813ebb5bb9ce12d329b744";

const repositoryRoot = resolve(fileURLToPath(new URL("..", import.meta.url)));
const metadataPath = resolve(
  repositoryRoot,
  "build",
  "s3-fixture-metadata.json",
);

function run(command, args, environment = process.env) {
  const result = spawnSync(command, args, {
    cwd: repositoryRoot,
    env: environment,
    shell: false,
    stdio: "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} exited ${result.status}`);
  }
}

function builderName() {
  return `careos-s3-fixture-${process.pid}-${Date.now().toString(36)}`;
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
  const name = builderName();
  const buildEnvironment = {
    ...process.env,
    SOURCE_DATE_EPOCH: FIXTURE_SOURCE_EPOCH,
    BUILDX_GIT_INFO: "0",
    BUILDX_METADATA_PROVENANCE: "disabled",
    BUILDX_NO_DEFAULT_ATTESTATIONS: "1",
  };
  let failure;
  let created = false;
  try {
    run("docker", [
      "buildx",
      "create",
      "--name",
      name,
      "--driver",
      "docker-container",
      "--driver-opt",
      `image=${FIXTURE_BUILDKIT_IMAGE}`,
    ]);
    created = true;
    run("docker", ["buildx", "inspect", "--builder", name, "--bootstrap"]);
    run(
      "docker",
      [
        "buildx",
        "build",
        "--builder",
        name,
        "--pull",
        "--provenance=false",
        "--platform",
        FIXTURE_PLATFORM,
        "--build-arg",
        `SOURCE_DATE_EPOCH=${FIXTURE_SOURCE_EPOCH}`,
        "--output",
        "type=docker,oci-mediatypes=false,rewrite-timestamp=true",
        "--metadata-file",
        metadataPath,
        "--tag",
        FIXTURE_IMAGE,
        "./test-fixtures/s3",
      ],
      buildEnvironment,
    );
    const digest = verifyFixtureMetadata(
      JSON.parse(readFileSync(metadataPath, "utf8")),
    );
    run("docker", ["run", "--rm", FIXTURE_IMAGE, "--version"]);
    process.stdout.write(
      `CareOS S3 compatibility fixture verified: ${FIXTURE_IMAGE} (${digest})\n`,
    );
  } catch (error) {
    failure = error;
  } finally {
    if (created) {
      try {
        run("docker", ["buildx", "rm", name]);
      } catch (cleanupError) {
        if (failure === undefined) {
          failure = cleanupError;
        } else {
          process.stderr.write(
            `Unable to remove fixture builder ${name}: ${cleanupError.message}\n`,
          );
        }
      }
    }
  }
  if (failure !== undefined) throw failure;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  buildAndVerifyFixture();
}
