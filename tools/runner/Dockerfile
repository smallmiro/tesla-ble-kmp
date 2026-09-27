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
# 컨테이너 메모리 한도(supervisor.sh: 5.5g) 안에서 Gradle 데몬, Kotlin 데몬, 테스트 JVM이 함께 돌도록 줄인다.
# 사용자 홈 gradle.properties는 프로젝트 gradle.properties(-Xmx4g)보다 우선한다. 한도를 넘기면 OOM으로 데몬이 죽는다.
RUN mkdir -p .gradle && printf '%s\n' \
    'org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' \
    'kotlin.daemon.jvmargs=-Xmx1g' \
    'org.gradle.workers.max=3' \
    > .gradle/gradle.properties
ENTRYPOINT ["/usr/local/bin/tesla-runner-entrypoint"]
