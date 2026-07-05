#!/bin/bash
# Produces a SemVer version from git tags — mirrors version_python.sh but for npm.
# Exact tag X.Y.Z → X.Y.Z (release); otherwise → X.Y.Z-dev.N (prerelease).

GIT_DESCRIBE=$(git describe --tags 2>/dev/null)

if [[ $GIT_DESCRIBE =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "$GIT_DESCRIBE"
elif [[ $GIT_DESCRIBE =~ ^([0-9]+\.[0-9]+\.[0-9]+)-([0-9]+)-g[0-9a-f]+ ]]; then
  echo "${BASH_REMATCH[1]}-dev.${BASH_REMATCH[2]}"
else
  echo "0.0.0-dev.$(git rev-list --count HEAD 2>/dev/null || echo 0)"
fi
