#!/bin/sh
# Gradle start-up script for POSIX. Requires gradle-wrapper.jar (generate via
# Android Studio import or `gradle wrapper`).
APP_HOME=$(cd "$(dirname "$0")" && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$CLASSPATH" ]; then
  echo "gradle-wrapper.jar missing. Run 'gradle wrapper' or open this project in Android Studio." >&2
  exit 1
fi
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Dorg.gradle.appname="gradlew" \
  -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
