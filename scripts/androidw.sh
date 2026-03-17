#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="${ROOT_DIR}"

if [ ! -x "${ANDROID_DIR}/gradlew" ]; then
  echo "ERROR: Gradle wrapper not found at ${ANDROID_DIR}/gradlew"
  exit 1
fi

resolve_java_home() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    echo "${JAVA_HOME}"
    return 0
  fi

  local local_jdk_home="${HOME}/.local/share/jdks/jdk-17.0.18+8"
  if [ -x "${local_jdk_home}/bin/java" ]; then
    echo "${local_jdk_home}"
    return 0
  fi

  if command -v javac >/dev/null 2>&1; then
    local javac_path
    javac_path="$(command -v javac)"
    local resolved
    resolved="$(readlink -f "${javac_path}" 2>/dev/null || true)"
    if [ -n "${resolved}" ]; then
      dirname "$(dirname "${resolved}")"
      return 0
    fi
  fi

  if command -v java >/dev/null 2>&1; then
    local java_path
    java_path="$(command -v java)"
    local resolved_java
    resolved_java="$(readlink -f "${java_path}" 2>/dev/null || true)"
    if [ -n "${resolved_java}" ]; then
      dirname "$(dirname "${resolved_java}")"
      return 0
    fi
  fi

  return 1
}

JAVA_HOME_RESOLVED="$(resolve_java_home || true)"
if [ -z "${JAVA_HOME_RESOLVED}" ]; then
  echo "ERROR: Java 17 not found."
  echo "Install JDK 17 and set JAVA_HOME, then rerun."
  exit 1
fi
export JAVA_HOME="${JAVA_HOME_RESOLVED}"

JAVA_MAJOR="$("${JAVA_HOME}/bin/java" -version 2>&1 | awk -F[\".] '/version/ {print $2; exit}')"
if [ -z "${JAVA_MAJOR}" ] || [ "${JAVA_MAJOR}" -lt 17 ]; then
  echo "ERROR: Java 17+ is required. Detected JAVA_HOME=${JAVA_HOME}."
  exit 1
fi

if [ -z "${GRADLE_USER_HOME:-}" ]; then
  export GRADLE_USER_HOME="/tmp/gradle-${USER:-dev}"
fi

if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  export ANDROID_SDK_ROOT="${ANDROID_HOME}"
fi
if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -d "${HOME}/Android/Sdk" ]; then
  export ANDROID_SDK_ROOT="${HOME}/Android/Sdk"
fi
if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -d "${HOME}/Library/Android/sdk" ]; then
  export ANDROID_SDK_ROOT="${HOME}/Library/Android/sdk"
fi
if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  export ANDROID_HOME="${ANDROID_SDK_ROOT}"
fi

LOCAL_PROPERTIES="${ANDROID_DIR}/local.properties"
if [ ! -f "${LOCAL_PROPERTIES}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  printf "sdk.dir=%s\n" "${ANDROID_SDK_ROOT}" > "${LOCAL_PROPERTIES}"
fi

cd "${ANDROID_DIR}"
exec ./gradlew "$@"
