#!/bin/bash

# The website is built using MkDocs with the Material theme.
# https://squidfunk.github.io/mkdocs-material/
# It requires python3 to run.

set -ex

REPO="git@github.com:lysine-dev/okhttp.git"
DIR=temp-clone

# Delete any existing temporary website clone
rm -rf $DIR

# Clone the current repo into temp folder
git clone $REPO $DIR
# Replace `git clone` with these lines to hack on the website locally
# cp -a . "../okhttp-website"
# mv "../okhttp-website" "$DIR"

# Move working directory into temp folder
cd $DIR

# Generate the API docs
./gradlew dokkaGeneratePublicationHtml -PokhttpDokka=true
mv ./build/dokka/html docs/5.x

# Copy in special files that GitHub wants in the project root.
cat README.md | grep -v 'project website' > docs/index.md
cp CHANGELOG.md docs/changelogs/changelog.md
cp CONTRIBUTING.md docs/contribute/contributing.md

# Build the site and push the new files up to GitHub
python3 -m venv venv
source venv/bin/activate
pip install mkdocs-material mkdocs-redirects
mkdocs gh-deploy

# Restore Javadocs from 1.x, 2.x, 3.x, and 4.x.
git checkout gh-pages
git cherry-pick bb229b9dcc9a21a73edbf8d936bea88f52e0a3ff
git cherry-pick c695732f1d4aea103b826876c077fbfea630e244
git cherry-pick 26c6d4f2f611690a29939044e897fb1f5221c2fb
git push --set-upstream origin gh-pages

# Delete our temp folder
cd ..
rm -rf $DIR
