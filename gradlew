#!/usr/bin/env bash
set -eu

DIR="$(cd "$(dirname "$0")" && pwd)"
APP_HOME="$DIR"
JAVACMD="${JAVA_HOME:+$JAVA_HOME/bin/java}"
if [ -z "${JAVACMD:-}" ]; then
  JAVACMD="java"
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$CLASSPATH" ]; then
  echo "Gradle wrapper JAR is missing. Generating it with Gradle 8.7..."
  if ! command -v gradle >/dev/null 2>&1; then
    echo "Gradle is not installed locally. Install Gradle 8.7 or run the CI workflow, which bootstraps the wrapper automatically." >&2
    exit 1
  fi
  gradle wrapper --gradle-version 8.7
  CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
fi

exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
