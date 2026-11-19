# Build environment for Cloudbase: JDK 17 + the Android SDK pieces the build needs.
# Used by scripts/gradle.sh so the host needs no Java or SDK.
FROM eclipse-temurin:17-jdk-jammy

ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip \
 && rm -rf /var/lib/apt/lists/*

ARG CMDLINE_TOOLS=13114758
RUN mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools \
 && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip

# build-tools 34 is AGP 8.7's default; platform-tools because release builds ask for it. World-writable so Gradle (running as the host
# user) can fetch anything else it decides it needs.
RUN { yes || true; } | sdkmanager --licenses >/dev/null \
 && sdkmanager "platforms;android-35" "build-tools;34.0.0" "platform-tools" >/dev/null \
 && chmod -R a+rwX $ANDROID_HOME
