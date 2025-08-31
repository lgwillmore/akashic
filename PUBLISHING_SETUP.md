# Sonatype Maven Central Publishing Setup

## Required GitHub Secrets

You need to configure the following secrets in your GitHub repository settings:

### 1. Central Portal Credentials
- `CENTRAL_PORTAL_USERNAME`: Your Sonatype Central Portal username
- `CENTRAL_PORTAL_PASSWORD`: Your Sonatype Central Portal password or user token

### 2. Signing Credentials (unchanged)
- `SONATYPE_SIGNING_KEY`: Your GPG private key in ASCII-armored format
- `SONATYPE_SIGNING_PASSWORD`: The passphrase for your GPG private key

## Setup Instructions

### 1. Create Sonatype Central Portal Account

1. Go to [https://central.sonatype.com/](https://central.sonatype.com/)
2. Sign up for a new account or log in with existing credentials
3. Verify your namespace (e.g., `codes.laurence.akashic`)

### 2. Generate User Token (Recommended)

Instead of using your password, generate a user token:

1. Log in to Central Portal
2. Go to Account → Generate User Token
3. Copy the username and password provided
4. Use these as your `CENTRAL_PORTAL_USERNAME` and `CENTRAL_PORTAL_PASSWORD`

### 3. Configure GitHub Secrets

In your GitHub repository:

1. Go to Settings → Secrets and variables → Actions
2. Add the following repository secrets:
   - `CENTRAL_PORTAL_USERNAME`
   - `CENTRAL_PORTAL_PASSWORD`
   - `SONATYPE_SIGNING_KEY` (if not already configured)
   - `SONATYPE_SIGNING_PASSWORD` (if not already configured)

### 4. GPG Key Setup (if not already done)

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

## Publishing Process

### Automatic Publishing

The GitHub Actions workflow will automatically publish when you trigger a release:

1. Go to Actions tab in your GitHub repository
2. Select "Release" workflow
3. Click "Run workflow"
4. Choose the version bump type (major, minor, patch)
5. The workflow will:
   - Create a new version tag
   - Build the project
   - Sign the artifacts
   - Publish to Maven Central

### Manual Publishing

You can also publish manually from your local machine:

```bash
# Set environment variables
export CENTRAL_PORTAL_USERNAME="your-username"
export CENTRAL_PORTAL_PASSWORD="your-password"
export SONATYPE_SIGNING_KEY="your-gpg-private-key"
export SONATYPE_SIGNING_PASSWORD="your-gpg-passphrase"

# Publish
./gradlew publish
```

## Migration from Legacy Setup

If you're migrating from the old OSSRH setup:

1. **Update GitHub Secrets**: Replace `SONATYPE_USERNAME` and `SONATYPE_PASSWORD` with `CENTRAL_PORTAL_USERNAME` and `CENTRAL_PORTAL_PASSWORD`
2. **Verify Namespace**: Ensure your namespace is verified in Central Portal
3. **Test Publishing**: Run a test publish with a snapshot version first

## Troubleshooting

### Getting Help

- [Sonatype Central Portal Documentation](https://central.sonatype.org/publish/)
- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Gradle Publishing Documentation](https://docs.gradle.org/current/userguide/publishing_maven.html)