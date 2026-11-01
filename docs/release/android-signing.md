# Android release signing

Use PKCS#12, an industry-standard keystore format, for release signing. The
Docker image's Eclipse Temurin/OpenJDK implementation, including `keytool`, is
[open-source software](https://adoptium.net/docs/faq/#is-eclipse-temurin-free-to-use).
The JKS warning concerns a vendor-specific storage format rather than the
license of the Java implementation.

The Dockerfile also downloads Google's prebuilt Android SDK tools and accepts
the [Android SDK license](https://developer.android.com/studio/terms).
Those terms distinguish SDK restrictions from individually open-source
components. A strictly FOSS toolchain still requires a component-level review
and any necessary replacements; changing the keystore format does not complete
that work.

## Convert or relabel an existing keystore

Convert the existing key instead of generating a replacement. Android updates
must retain the existing signing identity. Keep an encrypted backup of the
original keystore and its passwords before starting, and retain it until
conversion and release signing have been verified.

Run in a regular terminal after rebuilding `cofrinho-build`. Fill in the source
directory, filename, type, and alias from the existing keystore. The example
uses a PKCS#12 source; set `cofrinho_source_type=JKS` for a JKS source. The source
is mounted read-only, and the destination uses the new project name. If the
destination already exists, inspect it rather than overwriting its alias.

```bash
cofrinho_source_dir="/absolute/path/to/existing-signing-directory"
cofrinho_source_file="existing-release.p12"
cofrinho_source_type=PKCS12
cofrinho_source_alias="existing-alias"
cofrinho_signing_dir="$HOME/.local/share/cofrinho-signing"
mkdir -p "$cofrinho_signing_dir"
chmod 700 "$cofrinho_signing_dir"

docker run --rm -it \
  --user "$(id -u):$(id -g)" \
  --volume "$cofrinho_source_dir:/source:ro" \
  --volume "$cofrinho_signing_dir:/signing" \
  --entrypoint keytool \
  cofrinho-build \
  -importkeystore \
  -srckeystore "/source/$cofrinho_source_file" \
  -srcstoretype "$cofrinho_source_type" \
  -srcalias "$cofrinho_source_alias" \
  -destkeystore /signing/cofrinho-release.p12 \
  -deststoretype PKCS12 \
  -destalias cofrinho-release

chmod 600 "$cofrinho_signing_dir/cofrinho-release.p12"
```

Enter passwords at the interactive prompts. Use the existing private-key
password as the destination keystore password so the PKCS#12 store and key
passwords match. This can differ from the old JKS store password if the original
private key had its own password. The
[JDK 17 keytool documentation](https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html)
describes how imported entries retain their source protection password when no
destination key password is supplied.

## Verify identity before switching

Inspect both files with read-only mounts:

```bash
docker run --rm -it \
  --user "$(id -u):$(id -g)" \
  --volume "$cofrinho_source_dir:/source:ro" \
  --entrypoint keytool cofrinho-build \
  -list -v -keystore "/source/$cofrinho_source_file" \
  -storetype "$cofrinho_source_type" -alias "$cofrinho_source_alias"

docker run --rm -it \
  --user "$(id -u):$(id -g)" \
  --volume "$cofrinho_signing_dir:/signing:ro" \
  --entrypoint keytool cofrinho-build \
  -list -v -keystore /signing/cofrinho-release.p12 \
  -storetype PKCS12 -alias cofrinho-release
```

Both entries must be `PrivateKeyEntry` and display the same SHA-256 certificate
fingerprint. The destination alias is `cofrinho-release`; the source alias can
differ. Stop if the entry type or fingerprint differs, and continue using the
original keystore while investigating. An alias change is a label change, not
a replacement signing key. Verify a release APK signed with the destination
before removing any original keystore, and retain an encrypted backup.

## Configure release signing

Copy `keystore.properties.example` to the ignored `keystore.properties` file.
Set the real passwords locally, with the same password for `storePassword` and
`keyPassword`. The example points to `/signing/cofrinho-release.p12`, so mount the
signing directory at `/signing:ro` when building a release in Docker.

`storeType=PKCS12` selects the new format explicitly. Existing configurations
without `storeType` keep Android Gradle Plugin's existing default, allowing
conversion to be verified before switching clients. To roll back, point
`storeFile` to the original file and restore its original type, alias, and
passwords. Both files contain the same signing identity after a valid conversion.

For GitHub Actions, regenerate the base64 value outside the repository:

```bash
umask 077
base64 -w 0 "$cofrinho_signing_dir/cofrinho-release.p12" \
  > "$cofrinho_signing_dir/cofrinho-release.p12.base64"
```

Set `ANDROID_KEYSTORE_BASE64` to the new file's contents. Set
`ANDROID_STORE_PASSWORD` and `ANDROID_KEY_PASSWORD` to the matching PKCS#12
password, and keep `ANDROID_KEY_ALIAS=cofrinho-release`. The future release workflow
must reconstruct a `.p12` file and specify `storeType=PKCS12` in its temporary
signing properties. Repository secrets and workflows are not changed by editing
the example file.

Keystores and `keystore.properties` remain ignored by Git and excluded from
Docker's build context. Store the encoded keystore outside the repository too;
base64 is an encoding of the private file, not encryption.
