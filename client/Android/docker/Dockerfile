# syntax=docker/dockerfile:1.7
#
# Builds the share APK. Pick a target:
#
#   --target=binaries-debug    Debug APK, signed with an ephemeral debug
#                              keystore generated inside the container.
#                              Different keystore each build → app upgrades
#                              between two debug builds will be REJECTED by
#                              Android. Use for one-shot sideloads only.
#
#   --target=binaries-release  Release APK, signed with a keystore you
#                              provide as a BuildKit secret. This is what
#                              you actually ship.
#
# Examples (run from the REPO ROOT - the dir that has client/, server/, etc.):
#
#   docker build -f client/Android/docker/Dockerfile --target=binaries-debug \
#     --output=./out client/
#
#   docker build -f client/Android/docker/Dockerfile --target=binaries-release \
#     --secret id=keystore,src=./keystore/release.jks \
#     --secret id=keystore_pass,src=./keystore/storepass.txt \
#     --output=./out client/
#
# Build context is `client/` (not the repo root) - server/ and web/ carry
# multi-GB map data we don't want streamed to the daemon. We need `client/`
# rather than `client/Android/` so the build can see `client/Android.env.defaults`
# (the canonical build-time config schema) and optionally `client/Android.env`
# (per-fork override, gitignored). See gradle script in app/build.gradle.kts
# for the merge logic. Output lands in ./out/ at the repo root (share-debug.apk
# or share-release.apk). Keystore + password live in ./keystore/ at the repo
# root - see client/Android/docker/Dockerfile.keygen for generation.

ARG JDK_IMAGE=eclipse-temurin:21-jdk-noble
ARG CMDLINE_TOOLS_VERSION=11076708
ARG COMPILE_SDK=35
ARG BUILD_TOOLS_VERSION=35.0.0

# ── Stage 1: SDK install ─────────────────────────────────────────────────
FROM ${JDK_IMAGE} AS sdk
ARG CMDLINE_TOOLS_VERSION
ARG COMPILE_SDK
ARG BUILD_TOOLS_VERSION
ENV ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_HOME=/opt/android-sdk \
    BUILD_TOOLS_VERSION=${BUILD_TOOLS_VERSION}
ENV PATH="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools:${PATH}"

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        curl unzip ca-certificates && \
    rm -rf /var/lib/apt/lists/*

RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" && \
    cd /tmp && \
    curl -fsSL -o tools.zip \
      "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" && \
    unzip -q tools.zip -d "${ANDROID_SDK_ROOT}/cmdline-tools" && \
    mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" \
       "${ANDROID_SDK_ROOT}/cmdline-tools/latest" && \
    rm tools.zip

# `yes` feeds the licence prompts. Then install only what we need.
RUN yes | sdkmanager --licenses >/dev/null 2>&1 && \
    sdkmanager \
        "platform-tools" \
        "platforms;android-${COMPILE_SDK}" \
        "build-tools;${BUILD_TOOLS_VERSION}" >/dev/null

# ── Stage 2: Project sources + dep cache prime ───────────────────────────
FROM sdk AS sources
WORKDIR /src

# Build context is `client/` (not `client/Android/`) so `client/Android.env.defaults`
# is reachable - it sits sibling-of the Android module and gets injected as
# BuildConfig fields by the gradle script. `client/Android.env` (gitignored
# per-fork override) is also pulled in if present - the glob below handles
# both with one COPY. That's why every Android-side COPY below is prefixed
# with `Android/`.
COPY Android.env* ./

# Wrapper + version-pinning files first so the dep-cache layer doesn't bust
# every time a .kt file is touched.
COPY Android/gradle/ ./Android/gradle/
COPY Android/gradlew ./Android/
COPY Android/settings.gradle.kts Android/build.gradle.kts Android/gradle.properties ./Android/
COPY Android/app/build.gradle.kts ./Android/app/build.gradle.kts
COPY Android/protocol/build.gradle.kts ./Android/protocol/build.gradle.kts

# Drop into the gradle root for the rest of the build. `rootDir` from gradle's
# perspective is now /src/Android, and `rootDir.parentFile.resolve("Android.env.sample")`
# in app/build.gradle.kts resolves to /src/Android.env.sample - the file we
# copied in above.
WORKDIR /src/Android

# Prime the Gradle distribution + plugin classpath. BuildKit cache mount
# keeps /root/.gradle across rebuilds so we don't re-download Maven jars
# every time. --no-daemon: we want the JVM to exit before the layer commits.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon help >/dev/null

COPY Android/app/src ./app/src/
COPY Android/protocol/src ./protocol/src/

# Cache mounts for the three project-local Gradle dirs. Without these every
# build:
#   • runs a cold Kotlin compile (no incremental snapshot DB carried over) →
#     2 min instead of 2 s on a single-file rebuild,
#   • bakes the full ~700 MB-1 GB build output (intermediate jars, dex,
#     merged resources, kotlinc caches) into the RUN layer, which BuildKit
#     keeps as a content-cache snapshot. That's where your "every docker
#     build eats a gigabyte" goes.
#
# `sharing=locked` makes concurrent `docker build` invocations queue on the
# cache instead of corrupting it — Gradle assumes exclusive access to its
# build dir, so two parallel builds without the lock would tear each other's
# incremental state.

# ── Stage 3a: Debug ──────────────────────────────────────────────────────
FROM sources AS build-debug
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/src/Android/.gradle,sharing=locked \
    --mount=type=cache,target=/src/Android/app/build,sharing=locked \
    --mount=type=cache,target=/src/Android/protocol/build,sharing=locked \
    set -eux; \
    ./gradlew --no-daemon :app:assembleDebug; \
    mkdir -p /out; \
    cp app/build/outputs/apk/debug/app-debug.apk /out/share-debug.apk

FROM scratch AS binaries-debug
COPY --from=build-debug /out/ /

# ── Stage 3b: Release (signed) ───────────────────────────────────────────
FROM sources AS build-release
# Keystore + password ride in as BuildKit secrets (never written to any
# layer). zipalign first, then apksigner — same order Android Studio uses.
# --v1-signing-enabled=true is locked in because the Go server reads the
# signing cert out of META-INF/SHARE.RSA from the embedded APK to compute
# the publisher fingerprint shown in the app's About / version-check flow.
# Without v1, that META-INF entry doesn't exist (v2+ moves the signature
# out of the zip), and the fingerprint check breaks. apksigner's defaults
# at minSdk 28 include v1, but pinning it makes it explicit so a future
# minSdk bump to 30+ doesn't silently flip the default off.
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/src/Android/.gradle,sharing=locked \
    --mount=type=cache,target=/src/Android/app/build,sharing=locked \
    --mount=type=cache,target=/src/Android/protocol/build,sharing=locked \
    --mount=type=secret,id=keystore,target=/run/secrets/keystore,required=true \
    --mount=type=secret,id=keystore_pass,target=/run/secrets/keystore_pass,required=true \
    set -eux; \
    ./gradlew --no-daemon :app:assembleRelease; \
    UNSIGNED=app/build/outputs/apk/release/app-release-unsigned.apk; \
    ZIPALIGN="${ANDROID_SDK_ROOT}/build-tools/${BUILD_TOOLS_VERSION}/zipalign"; \
    APKSIGNER="${ANDROID_SDK_ROOT}/build-tools/${BUILD_TOOLS_VERSION}/apksigner"; \
    "${ZIPALIGN}" -p 4 "${UNSIGNED}" /tmp/aligned.apk; \
    "${APKSIGNER}" sign \
        --ks /run/secrets/keystore \
        --ks-pass "file:/run/secrets/keystore_pass" \
        --v1-signing-enabled=true \
        --out /tmp/signed.apk \
        /tmp/aligned.apk; \
    "${APKSIGNER}" verify --verbose /tmp/signed.apk; \
    mkdir -p /out; \
    cp /tmp/signed.apk /out/share-release.apk

FROM scratch AS binaries-release
COPY --from=build-release /out/ /
