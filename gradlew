#!/bin/sh

# Licensed under the Apache License, Version 2.0.

app_path=$0
while [ -h "$app_path" ]; do
    link=$(ls -ld "$app_path")
    link=${link#*' -> '}
    case $link in
      /*) app_path=$link ;;
      *) app_path=$(dirname "$app_path")/$link ;;
    esac
done

APP_HOME=$(cd -P "$(dirname "$app_path")" >/dev/null 2>&1 && pwd) || exit
APP_NAME="Gradle"
APP_BASE_NAME=${0##*/}

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

cygwin=false
msys=false
darwin=false
nonstop=false
case "$(uname)" in
  CYGWIN*) cygwin=true ;;
  Darwin*) darwin=true ;;
  MSYS* | MINGW*) msys=true ;;
  NONSTOP*) nonstop=true ;;
esac

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ]; then
        echo "ERROR: JAVA_HOME points to an invalid directory: $JAVA_HOME" >&2
        exit 1
    fi
else
    JAVACMD=java
    command -v java >/dev/null 2>&1 || {
        echo "ERROR: JAVA_HOME is not set and no java command could be found." >&2
        exit 1
    }
fi

if "$cygwin" || "$msys"; then
    APP_HOME=$(cygpath --path --mixed "$APP_HOME")
    CLASSPATH=$(cygpath --path --mixed "$CLASSPATH")
    JAVACMD=$(cygpath --unix "$JAVACMD")
fi

set -- \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"

command -v xargs >/dev/null 2>&1 || {
    echo "xargs is not available" >&2
    exit 1
}

eval "set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \"\$@\""
exec "$JAVACMD" "$@"
