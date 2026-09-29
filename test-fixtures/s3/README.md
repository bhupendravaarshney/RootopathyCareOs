# CareOS S3 compatibility fixture

This directory defines the synthetic S3-compatible server used only by automated tests and the local Compose stack. It is not a production storage recommendation.

The fixture builds MinIO `RELEASE.2025-09-07T16-13-09Z` from verified source commit `07c3a429bfed433e49018cb0f78a52145d4bedeb`. The GitHub source archive is fixed by SHA-256, the Go builder is fixed by a multi-architecture image digest, and the final image is a non-root scratch image. No removed MinIO registry image or mutable application image is used.

`node scripts/build-s3-test-fixture.mjs` creates an ephemeral builder from a digest-pinned BuildKit image, uses a digest-pinned Dockerfile frontend, fixes the platform and release timestamp, disables environment-derived VCS/provenance data, rewrites layer timestamps to `SOURCE_DATE_EPOCH`, and emits a single Docker V2 manifest that loads into both classic and containerd-backed Docker stores. It then rejects any result other than manifest digest `sha256:9b225075e9847bde86fa19f8274353ae7c5c4018af813ebb5bb9ce12d329b744`. Testcontainers uses the verified local tag with pulling disabled. This is equivalent to a digest lock inside the isolated build job while avoiding a dependency on an unpublished or mutable registry artifact.

The explicit layer rewrite is required: `SOURCE_DATE_EPOCH` alone normalizes image configuration/history timestamps but does not normalize file timestamps inside image layers. The builder is removed after every successful or failed preflight so the verification does not depend on a persistent local cache.

The fixture remains an external-provider compatibility boundary. Its tests are required for release and cover private buckets, tenant-derived keys, length and SHA-256 checks, replay/conflict handling, quarantine, clean promotion, signed reads, Object Lock compliance retention, and one-way legal-hold enablement.
