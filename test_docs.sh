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

# Copy in special files that GitHub wants in the project root.
cat README.md | grep -v 'project website' > docs/index.md
cp CHANGELOG.md docs/changelogs/changelog.md
cp CONTRIBUTING.md docs/contribute/contributing.md

# Build the site locally
mkdocs build
