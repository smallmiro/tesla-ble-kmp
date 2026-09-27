# tools/runner/Dockerfile — CI용 ephemeral Linux runner 이미지 (ADR-0012)
# linux/amd64로 빌드한다. Android 빌드 도구(aapt2)의 Linux 배포판이 x86_64 전용이기 때문이다.
FROM ghcr.io/actions/actions-runner:2.337.0

USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git jq unzip xz-utils zip \
 && rm -rf /var/lib/apt/lists/*

COPY --chmod=755 entrypoint.sh /usr/local/bin/tesla-runner-entrypoint

USER runner
WORKDIR /home/runner
ENTRYPOINT ["/usr/local/bin/tesla-runner-entrypoint"]
