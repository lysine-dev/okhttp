Releasing
=========

1. Update `CHANGELOG.md`.

2. Set versions:

    ```
    export RELEASE_VERSION=X.Y.Z
    export NEXT_VERSION=X.Y.Z-SNAPSHOT
    ```

3. Update versions, tag the release, and prepare for the next release.

    ```
    sed -i "" \
      "s/version = \".*\"/version = \"$RELEASE_VERSION\"/g" \
      build-logic/src/main/kotlin/okhttp.base-conventions.gradle.kts
    sed -i "" \
      "s/\"com.squareup.okhttp3:\([^\:]*\):[^\"]*\"/\"com.squareup.okhttp3:\1:$RELEASE_VERSION\"/g" \
      `find . -name "README.md"`
    sed -i "" \
      "s/\/com.squareup.okhttp3\/\([^\:]*\)\/[^\/]*\//\/com.squareup.okhttp3\/\1\/$RELEASE_VERSION\//g" \
      `find . -name "README.md"`

    git commit -am "Prepare for release $RELEASE_VERSION."
    git tag -a parent-$RELEASE_VERSION -m "Version $RELEASE_VERSION"
    git push && git push --tags

   sed -i "" \
      "s/version = \".*\"/version = \"$NEXT_VERSION\"/g" \
      build-logic/src/main/kotlin/okhttp.base-conventions.gradle.kts
    git commit -am "Prepare next development version."
    git push
    ```

4. Wait for [GitHub Actions][github_actions] to build and promote the release.

[github_actions]: https://github.com/lysine-dev/okhttp/actions
