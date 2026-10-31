#!/usr/bin/env sh
set -eu

if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle is required. Install Gradle with JDK 17, then rerun: ./gradlew $*" >&2
  exit 127
fi

exec gradle "$@"
