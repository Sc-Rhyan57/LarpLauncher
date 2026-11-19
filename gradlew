#!/bin/sh

#
# Copyright (c) 2024-2026 Larp Launcher Contributors
# Licensed under GPLv3 — see LICENSE.
#

# Resolve script directory
APP_HOME=$(cd "$(dirname "$0")" && pwd)

# Find java
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Use wrapper jar if present
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$CLASSPATH" ]; then
    echo "gradle-wrapper.jar ausente. Execute ./setup.sh para baixar."
    exit 1
fi

exec "$JAVACMD" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
