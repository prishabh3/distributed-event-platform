#!/bin/sh
# Maven Wrapper script — downloads Maven 3.9.6 on first run if not cached.
# Usage: ./mvnw clean package -DskipTests

set -e

MAVEN_WRAPPER_JAR=".mvn/wrapper/maven-wrapper.jar"
MAVEN_WRAPPER_PROPERTIES=".mvn/wrapper/maven-wrapper.properties"

# Read distribution URL
DISTRIBUTION_URL=$(grep distributionUrl "$MAVEN_WRAPPER_PROPERTIES" | cut -d= -f2-)

# Determine MAVEN_USER_HOME
if [ -z "$MAVEN_USER_HOME" ]; then
    MAVEN_USER_HOME="$HOME/.m2"
fi

MAVEN_HOME="$MAVEN_USER_HOME/wrapper/dists/$(basename "$DISTRIBUTION_URL" .zip)"

if [ ! -f "$MAVEN_HOME/bin/mvn" ]; then
    echo "Downloading Maven from $DISTRIBUTION_URL ..."
    mkdir -p "$MAVEN_HOME"
    TMP_ZIP=$(mktemp /tmp/maven-XXXXXX.zip)
    curl -fsSL "$DISTRIBUTION_URL" -o "$TMP_ZIP"
    unzip -q "$TMP_ZIP" -d "$MAVEN_HOME" && rm "$TMP_ZIP"
    # Maven zip extracts as apache-maven-x.y.z/ — flatten one level
    INNER_DIR=$(ls "$MAVEN_HOME" | head -1)
    mv "$MAVEN_HOME/$INNER_DIR"/* "$MAVEN_HOME/" 2>/dev/null || true
    rmdir "$MAVEN_HOME/$INNER_DIR" 2>/dev/null || true
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
