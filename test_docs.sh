#!/bin/bash

# The website is built using MkDocs with the Material theme.
# https://squidfunk.github.io/mkdocs-material/
# It requires Python to run.
# Install the packages with the following command:
# pip install mkdocs mkdocs-material mkdocs-redirects

set -ex

# Test generating the javadoc jars
./gradlew publishToMavenLocal -DRELEASE_SIGNING_ENABLED=false -PokhttpDokka=true

# Generate the API docs
./gradlew dokkaGeneratePublicationHtml -PokhttpDokka=true

mv ./build/dokka/html docs/5.x

# Redirect 4.x doc URLs to 5.x.
# The 4.x API docs were never published to gh-pages, so all /4.x/ URLs 404.
# Since 5.x is the direct successor, redirect there.
mkdir -p docs/4.x
cat > docs/4.x/index.html << 'REDIRECT'
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <title>Redirecting to OkHttp 5.x API docs</title>
  <script>
    // Redirect /4.x/anything to /5.x/anything
    var newPath = window.location.pathname.replace('/4.x/', '/5.x/');
    window.location.replace(window.location.origin + newPath + window.location.hash);
  </script>
  <meta http-equiv="refresh" content="0; url=../5.x/">
</head>
<body>
  <p>OkHttp 4.x API docs are no longer published separately. Redirecting to <a href="../5.x/">5.x API docs</a>.</p>
</body>
</html>
REDIRECT

# Copy in special files that GitHub wants in the project root.
cat README.md | grep -v 'project website' > docs/index.md
cp CHANGELOG.md docs/changelogs/changelog.md
cp CONTRIBUTING.md docs/contribute/contributing.md

# Build the site locally
mkdocs build
