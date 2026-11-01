# syntax=docker/dockerfile:1

# Generate the upstream wrapper without evaluating Caixa's Android build.
FROM gradle:8.11.1-jdk17 AS wrapper-generator
USER root
WORKDIR /opt/caixa-wrapper
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties
RUN touch settings.gradle.kts \
    && gradle --no-daemon --offline wrapper \
        --gradle-version 8.11.1 \
        --distribution-type bin \
        --gradle-distribution-sha256-sum f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6 \
        --no-validate-url \
    && printf '%s  %s\n' \
        '2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046' \
        'gradle/wrapper/gradle-wrapper.jar' | sha256sum --check
# Keep the committed distribution settings as the source of wrapper configuration.
COPY gradle/wrapper/gradle-wrapper.properties gradle/wrapper/gradle-wrapper.properties

# Export these files once, then commit the official scripts and wrapper JAR.
FROM scratch AS wrapper-files
COPY --from=wrapper-generator /opt/caixa-wrapper/gradlew /gradlew
COPY --from=wrapper-generator /opt/caixa-wrapper/gradlew.bat /gradlew.bat
COPY --from=wrapper-generator /opt/caixa-wrapper/gradle/wrapper/ /gradle/wrapper/

FROM eclipse-temurin:17-jdk-jammy AS android-builder
USER root
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/workspace/.gradle-docker
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/build-tools/35.0.0:${PATH}"

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl unzip \
    && apt-get clean
RUN curl --fail --location --retry 3 \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
        --output /tmp/android-commandline-tools.zip \
    && printf '%s  %s\n' \
        '2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258' \
        '/tmp/android-commandline-tools.zip' | sha256sum --check \
    && mkdir --parents "${ANDROID_HOME}/cmdline-tools" \
    && unzip -q /tmp/android-commandline-tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
    && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
    && rm /tmp/android-commandline-tools.zip

# Building this image accepts Google's Android SDK licenses for this build environment.
RUN yes | sdkmanager --sdk_root="${ANDROID_HOME}" --licenses > /dev/null
RUN sdkmanager --sdk_root="${ANDROID_HOME}" \
        "platform-tools" "platforms;android-36" "build-tools;35.0.0"

ENV ANDROID_USER_HOME=/workspace/.android-docker
COPY --from=wrapper-generator /opt/caixa-wrapper/ /opt/caixa-wrapper/
WORKDIR /workspace
COPY . .
ENTRYPOINT ["/opt/caixa-wrapper/gradlew", "--no-daemon"]
CMD ["check", ":apps:android:assembleDebug"]
