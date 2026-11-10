# Publishing

The library modules (`akashic-core`, `akashic-backend-exposed`, `akashic-backend-kurrent`) apply the
`buildsrc.convention.publish` convention, which publishes them to Maven Central with the
[vanniktech Maven Publish plugin](https://vanniktech.github.io/gradle-maven-publish-plugin/): jar, sources, an empty
javadoc jar, Gradle module metadata and POM, all signed. Test fixtures and `integration-tests` are never published.

## Versions

The version comes from git. A commit carrying a release tag (`X.Y.Z`, no `v` prefix) builds that version; any other
commit builds a `-SNAPSHOT` such as `0.1.0-3-gabc1234-SNAPSHOT`, which can never become a Maven Central release. Only
the Release workflow creates release tags.

## Releasing

Go to Actions → Release → Run workflow on `main`, and choose `patch`, `minor` or `major`. The workflow:

1. runs the Check workflow (ktfmt and the full test suite);
2. tags the commit locally with the next version, bumped from the highest `X.Y.Z` tag;
3. runs `./gradlew publishAndReleaseToMavenCentral`, which uploads all modules as one deployment, releases it, and
   waits for Central to validate it;
4. pushes the tag and creates a GitHub release with generated notes.

A failed check or publish pushes no tag, so the workflow can simply be run again. A Maven Central release is
permanent: a version can never be replaced or deleted. It is usually downloadable within 30 minutes and searchable
on central.sonatype.com a little later. If publishing succeeded but the tag push failed, push the tag by hand
(`git tag -a X.Y.Z <commit> -m "Release X.Y.Z" && git push origin X.Y.Z`); re-running would fail because Central
rejects an existing version.

## One-time setup

1. Sign in to the [Central Portal](https://central.sonatype.com/).
2. Verify the `codes.laurence` namespace: Publishing → Namespaces → Add Namespace, then add the TXT record it shows to
   the DNS of `laurence.codes` and press Verify. (Without control of that domain, change `group` in the root
   `build.gradle.kts` to `io.github.lgwillmore`, which is verified automatically when signed in with GitHub.)
3. Generate a user token: Account → Generate User Token.
4. Create a signing key and publish its public half, which Central uses to check the signatures:

   ```shell
   gpg --quick-gen-key "Your Name <you@example.com>" ed25519 sign never
   gpg --list-secret-keys --keyid-format long          # the key ID follows "ed25519/"
   gpg --keyserver keyserver.ubuntu.com --send-keys KEY_ID
   gpg --keyserver keys.openpgp.org --send-keys KEY_ID
   gpg --armor --export-secret-keys KEY_ID             # the value of SONATYPE_SIGNING_KEY
   ```

5. Add the repository secrets under Settings → Secrets and variables → Actions:

   | Secret | Value |
   | --- | --- |
   | `MAVEN_CENTRAL_PORTAL_USERNAME` | user token username |
   | `MAVEN_CENTRAL_PORTAL_PASSWORD` | user token password |
   | `SONATYPE_SIGNING_KEY` | ASCII-armored private key, including the `BEGIN`/`END` lines |
   | `SONATYPE_SIGNING_PASSWORD` | the key's passphrase |

## Publishing from a machine

`./gradlew publishToMavenLocal` installs the current snapshot in `~/.m2` for trying it in another project; snapshots
need no signing key.

To rehearse a release without making it public, tag a commit locally (`git tag 0.1.0`, never pushed), provide the
credentials, and upload a deployment that waits for a manual decision:

```shell
export ORG_GRADLE_PROJECT_mavenCentralUsername=...
export ORG_GRADLE_PROJECT_mavenCentralPassword=...
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys KEY_ID)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=...
./gradlew publishToMavenCentral
```

Inspect the deployment under Publishing → Deployments in the Central Portal, then drop it there and delete the local
tag (`git tag -d 0.1.0`). Pressing Publish there instead makes it a permanent release that has no pushed tag.
