#!/bin/bash
set -e

# Validate required environment variables
if [[ -z "$NEXUS_URL" || -z "$NEXUS_REPO_ID" ]]; then
  echo "::error::Missing required environment variables: NEXUS_URL or NEXUS_REPO_ID"
  exit 1
fi

echo "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
echo " PUBLISH TO NEXUS (via deploy-file)"
echo "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"

# 1. Define common arguments for all uploads
# We use an array to keep the command clean and safe
COMMON_ARGS=(
  "-B"
  "deploy:deploy-file"
  "-Durl=${NEXUS_URL}"
  "-DrepositoryId=${NEXUS_REPO_ID}"
  "-DpomFile=pom.xml"
  "-DgeneratePom=false"
  "-DretryFailedDeploymentCount=3"
)

# 2. Find and Deploy the Main JAR (excluding classifiers)
# This excludes tests, sources, javadocs, etc., to find the actual library.
MAIN_JAR=$(find target -maxdepth 1 -name "*.jar" \
  ! -name "*-tests.jar" \
  ! -name "*-sources.jar" \
  ! -name "*-test-sources.jar" \
  ! -name "*-javadoc.jar" \
  ! -name "*-runner.jar" \
  | head -n 1)

if [ -n "$MAIN_JAR" ]; then
  echo ">> Deploying Main Artifact: $MAIN_JAR"
  mvn "${COMMON_ARGS[@]}" -Dfile="$MAIN_JAR"
else
  echo "::error::Main JAR not found in target/! Build failed or artifacts missing."
  exit 1
fi

# 3. Find and Deploy Attached Artifacts
# We loop through specific suffixes to map them to Maven classifiers
declare -A ARTIFACTS
ARTIFACTS["-tests.jar"]="tests"
ARTIFACTS["-sources.jar"]="sources"
ARTIFACTS["-test-sources.jar"]="test-sources"
ARTIFACTS["-javadoc.jar"]="javadoc"

for SUFFIX in "${!ARTIFACTS[@]}"; do
  CLASSIFIER="${ARTIFACTS[$SUFFIX]}"
  FILE=$(find target -maxdepth 1 -name "*$SUFFIX" | head -n 1)

  if [ -n "$FILE" ]; then
    echo ">> Deploying Classifier [$CLASSIFIER]: $FILE"
    mvn "${COMMON_ARGS[@]}" -Dfile="$FILE" -Dclassifier="$CLASSIFIER"
  else
    echo "   (Skipping $CLASSIFIER: file not found)"
  fi
done

echo "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
echo " PUBLISH COMPLETE"
echo "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"