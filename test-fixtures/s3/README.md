# CareOS S3 compatibility fixture

This directory defines the synthetic S3-compatible server used only by automated tests and the local Compose stack. It is not a production storage recommendation.

The fixture builds MinIO `RELEASE.2025-09-07T16-13-09Z` from verified source commit `07c3a429bfed433e49018cb0f78a52145d4bedeb`. The GitHub source archive is fixed by SHA-256, the Go builder is fixed by a multi-architecture image digest, and the final image is a non-root scratch image. No removed MinIO registry image or mutable application image is used.

`node scripts/build-s3-test-fixture.mjs` builds the fixed `linux/amd64` fixture with provenance disabled and the release timestamp as `SOURCE_DATE_EPOCH`, then rejects any result other than manifest digest `sha256:bb6f358423eec8c666f70d24dbab12a0b9467b5071f2bb30ee64767d3dce82d1`. Testcontainers uses the verified local tag with pulling disabled. This is equivalent to a digest lock inside the isolated build job while avoiding a dependency on an unpublished or mutable registry artifact.

The fixture remains an external-provider compatibility boundary. Its tests are required for release and cover private buckets, tenant-derived keys, length and SHA-256 checks, replay/conflict handling, quarantine, clean promotion, signed reads, Object Lock compliance retention, and one-way legal-hold enablement.
