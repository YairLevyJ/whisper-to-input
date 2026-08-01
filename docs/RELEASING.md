# Releasing

The `Release` workflow builds the distributable APK, attaches a GitHub-signed provenance
attestation to it, and publishes it as a GitHub Release. It is run manually from the
Actions tab.

## Why local debug builds differ from CI builds

A local `./gradlew assembleDebug` produces a **debug** APK: `android:debuggable` is `true`
(anything with ADB access can attach a debugger and read process memory, including the API
key), and it is signed with a key generated fresh by the machine that built it — so two debug
builds, even from identical source, cannot be installed over one another. Android refuses an
update whose signing certificate differs from the installed one, so the old copy has to be
uninstalled first, which also wipes the settings.

Both the `Build` and `Release` workflows instead build the **release** build type — not
debuggable, and signed with the shared keystore below — so every build from either workflow
installs cleanly over the last one. They differ only in what happens to the result:

| | `Build` workflow | `Release` workflow |
|---|---|---|
| Purpose | Try a branch on your own device before releasing | Publish a distributable version |
| Output | Workflow artifact only | GitHub Release, with the APK attached |
| Build provenance attestation | No | Yes |
| Version tag | No | Yes, the one you give it when running it |

Both need the same four signing secrets set up below.

## One-time setup: create the signing keystore

Android has no concept of a signature "from GitHub" — the APK has to be signed by a key you
own. Create it once, on a machine you control, and keep it safe: if it is lost, future
releases can no longer update an installed copy of the app.

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias whisper-to-input \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storetype PKCS12
```

`keytool` ships with any JDK. It will ask for a password and for a name/organisation; the
name fields are cosmetic for a private app and can be anything.

Then encode the keystore so it can be stored as a secret:

```bash
base64 -w 0 release.jks    # macOS: base64 -i release.jks
```

## One-time setup: add the repository secrets

Under **Settings → Secrets and variables → Actions → New repository secret**, add:

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the base64 output from above |
| `RELEASE_KEYSTORE_PASSWORD` | the keystore password |
| `RELEASE_KEY_ALIAS` | `whisper-to-input` (or whatever `-alias` you used) |
| `RELEASE_KEY_PASSWORD` | the key password (same as the store password unless you set a different one) |

Back up `release.jks` somewhere durable. GitHub secrets cannot be read back out.

## Cutting a release

**Actions → Release → Run workflow**, and give it a tag such as `v1.0`.

The workflow refuses to publish an unsigned APK: it runs `apksigner verify` before creating
the release, so a misconfigured secret fails the run instead of shipping something that
cannot be installed.

## Verifying a published APK

The release carries a build provenance attestation, signed by GitHub's Sigstore instance
and bound to this repository's workflow identity. Anyone can verify that a downloaded APK
was genuinely produced by this repository, from a specific commit, and has not been altered:

```bash
gh attestation verify app-release.apk --repo <owner>/<repo>
```

This is the part that proves origin. The APK's own signature proves continuity between
versions — that a new APK comes from the same signer as the installed one — but on its own
it says nothing about which source code produced it.
