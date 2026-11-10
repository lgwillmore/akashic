# Publishing

## GitHub Packages (current)

Library modules apply the `buildsrc.convention.publish` convention, which creates the `maven` publication (jar,
sources, javadoc, POM; test fixtures are left out) and the `GitHubPackages` repository.

Releasing needs no secrets:

1. Go to the Actions tab and run the "Release" workflow on `main`, choosing the bump (major, minor, patch).
2. It tags the commit (no `v` prefix, e.g. `0.2.0`), and the versioning plugin turns that tag into the version.
3. It runs `./gradlew publishAllPublicationsToGitHubPackagesRepository` with the workflow's `GITHUB_TOKEN`.

To publish from a machine instead, set `gpr.user` (GitHub user) and `gpr.key` (a token with `write:packages`) in
`~/.gradle/gradle.properties`, or export `GITHUB_ACTOR` and `GITHUB_TOKEN`, then run the same task. Untagged commits
get a development version such as `0.1.0+dev.1.1c2b0ee`.

Consumers need a token with `read:packages`, even for public packages; see the README.

## Maven Central (later, not wired up)

`buildsrc.convention.sonatype-publish` layers signing and JReleaser's Maven Central deploy on top of the publish
convention. To switch, apply it in place of `buildsrc.convention.publish` in each library module and give the Release
workflow a JReleaser deploy step with the secrets below.

### Required GitHub Secrets

You need to configure the following secrets in your GitHub repository settings:

#### 1. Central Portal Credentials
- `MAVEN_CENTRAL_PORTAL_USERNAME`: Your Sonatype Central Portal username
- `MAVEN_CENTRAL_PORTAL_PASSWORD`: Your Sonatype Central Portal password or user token

#### 2. Signing Credentials (unchanged)
- `SONATYPE_SIGNING_KEY`: Your GPG private key in ASCII-armored format
- `SONATYPE_SIGNING_PASSWORD`: The passphrase for your GPG private key

### Setup Instructions

#### 1. Create Sonatype Central Portal Account

1. Go to [https://central.sonatype.com/](https://central.sonatype.com/)
2. Sign up for a new account or log in with existing credentials
3. Verify your namespace (e.g., `codes.laurence.akashic`)

#### 2. Generate User Token (Recommended)

Instead of using your password, generate a user token:

1. Log in to Central Portal
2. Go to Account → Generate User Token
3. Copy the username and password provided
4. Use these as your `MAVEN_CENTRAL_PORTAL_USERNAME` and `MAVEN_CENTRAL_PORTAL_PASSWORD`

#### 3. Configure GitHub Secrets

In your GitHub repository:

1. Go to Settings → Secrets and variables → Actions
2. Add the following repository secrets:
   - `MAVEN_CENTRAL_PORTAL_USERNAME`
   - `MAVEN_CENTRAL_PORTAL_PASSWORD`
   - `SONATYPE_SIGNING_KEY` (if not already configured)
   - `SONATYPE_SIGNING_PASSWORD` (if not already configured)

#### 4. GPG Key Setup (if not already done)

If you don't have GPG keys set up:

```bash
# Generate a new GPG key
gpg --gen-key

# Export the private key in ASCII format
gpg --armor --export-secret-keys YOUR_KEY_ID

# Export the public key
gpg --armor --export YOUR_KEY_ID
```

Upload your public key to key servers:
```bash
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
gpg --keyserver keys.openpgp.org --send-keys YOUR_KEY_ID
```

### Publishing Process

#### Automatic Publishing

Once the Release workflow has a deploy step, triggering a release will:

1. Create a new version tag
2. Build the project
3. Sign the artifacts
4. Publish to Maven Central

#### Manual Publishing

With the convention applied, you can also publish from your local machine:

```bash
# Set environment variables
export MAVEN_CENTRAL_PORTAL_USERNAME="your-username"
export MAVEN_CENTRAL_PORTAL_PASSWORD="your-password"
export SONATYPE_SIGNING_KEY="your-gpg-private-key"
export SONATYPE_SIGNING_PASSWORD="your-gpg-passphrase"

# Stage the artifacts, then deploy them
./gradlew publish
./gradlew jreleaserDeploy
```

### Troubleshooting

#### Getting Help

- [Sonatype Central Portal Documentation](https://central.sonatype.org/publish/)
- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Gradle Publishing Documentation](https://docs.gradle.org/current/userguide/publishing_maven.html)