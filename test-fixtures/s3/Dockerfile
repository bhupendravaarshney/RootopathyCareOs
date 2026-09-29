FROM golang:1.24-alpine@sha256:8bee1901f1e530bfb4a7850aa7a479d17ae3a18beb6e09064ed54cfd245b7191 AS build

ARG MINIO_RELEASE=RELEASE.2025-09-07T16-13-09Z
ARG MINIO_COMMIT=07c3a429bfed433e49018cb0f78a52145d4bedeb
ARG MINIO_SOURCE_SHA256=c9598dcce3440977e79f787f2ba0e7e4d92c8d556bd51e7cef3785bafd6635f3

ENV CGO_ENABLED=0 \
    GOFLAGS=-buildvcs=false \
    GOTOOLCHAIN=local

WORKDIR /src

ADD --checksum=sha256:c9598dcce3440977e79f787f2ba0e7e4d92c8d556bd51e7cef3785bafd6635f3 \
    https://codeload.github.com/minio/minio/tar.gz/refs/tags/RELEASE.2025-09-07T16-13-09Z \
    /tmp/minio.tar.gz

RUN test "$(sha256sum /tmp/minio.tar.gz | cut -d ' ' -f 1)" = "$MINIO_SOURCE_SHA256" \
    && tar -xzf /tmp/minio.tar.gz --strip-components=1 -C /src \
    && go build \
        -mod=readonly \
        -tags kqueue \
        -trimpath \
        -ldflags "-s -w -X github.com/minio/minio/cmd.Version=2025-09-07T16:13:09Z -X github.com/minio/minio/cmd.CopyrightYear=2025 -X github.com/minio/minio/cmd.ReleaseTag=${MINIO_RELEASE} -X github.com/minio/minio/cmd.CommitID=${MINIO_COMMIT} -X github.com/minio/minio/cmd.ShortCommitID=07c3a429bfed" \
        -o /out/minio . \
    && mkdir -p /fixture-root/data /fixture-root/home/nonroot /fixture-root/tmp \
    && chown -R 65532:65532 /fixture-root/data /fixture-root/home/nonroot \
    && chmod 0700 /fixture-root/data /fixture-root/home/nonroot \
    && chmod 1777 /fixture-root/tmp

FROM scratch

LABEL org.opencontainers.image.title="CareOS S3 compatibility test fixture" \
      org.opencontainers.image.description="Synthetic automated-test fixture; not a production storage recommendation" \
      org.opencontainers.image.source="https://github.com/bhupendravaarshney/RootopathyCareOs" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later" \
      org.opencontainers.image.version="RELEASE.2025-09-07T16-13-09Z" \
      org.opencontainers.image.revision="07c3a429bfed433e49018cb0f78a52145d4bedeb"

ENV HOME=/home/nonroot \
    PATH=/usr/bin \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt

COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /out/minio /usr/bin/minio
COPY --from=build --chown=65532:65532 /fixture-root/ /

USER 65532:65532
EXPOSE 9000
VOLUME ["/data"]
ENTRYPOINT ["/usr/bin/minio"]
