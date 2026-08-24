# Building ForestNote

[← Documentation index](../README.md)

ForestNote is a multi-repository build. A fresh checkout of this repository alone is not enough:
the Viwoods ink library is a Gradle composite-build sibling, and RhizomeSync is resolved from the
local Maven repository.

## Toolchain

- JDK 21
- Android SDK platform 35 and Build Tools 35.0.0
- Git
- an Android SDK license/configuration accepted by Gradle

The app has `minSdk 30` and targets Android 11 compatibility behavior. The compiler toolchain is
JDK 21 and emits JVM 11 bytecode.

## Checkout layout

Keep these repositories beside one another:

```text
src/
├── ForestNote/
├── vw_ink_sdk_unofficial/
└── rhizome/
```

For a build matching ForestNote 2.0, use the dependency versions pinned by the release workflow:

```sh
cd src
git clone https://github.com/jdkruzr/ForestNote.git
git clone --branch v0.2.0 https://github.com/jdkruzr/vw_ink_sdk_unofficial.git
git clone --branch v0.8.2 https://github.com/jdkruzr/rhizome.git
```

The sibling directory name `vw_ink_sdk_unofficial` is significant because `settings.gradle.kts`
includes that exact relative path. UltraBridge is not required to compile or use ForestNote; it is
only needed to exercise optional network sync.

## Publish Rhizome locally

Before the first ForestNote build—or after changing the Rhizome checkout—publish its Kotlin client
artifacts:

```sh
cd src/rhizome/client-kotlin
./gradlew publishToMavenLocal
```

Keep the Rhizome tag in lockstep with `rhizome` in `gradle/libs.versions.toml` and the checkout in
`.github/workflows/release.yml`.

## Build and test

```sh
cd src/ForestNote
./gradlew test
./gradlew :app:notes:assembleDebug
```

The debug APK is written to:

```text
app/notes/build/outputs/apk/debug/notes-debug.apk
```

The Boox SDK Maven repository is HTTP-only; the repository declarations deliberately allow
cleartext access only for the Boox hosts. The Viwoods dependency is compiled from the sibling source
checkout rather than downloaded as a binary.

## Signed releases and safe upgrades

Official APKs are built by `.github/workflows/release.yml`. A `v*` tag creates a signed GitHub
Release; a manual workflow run produces a signed candidate artifact without publishing a release.
The workflow pins the Viwoods and Rhizome source tags so the shipped dependency code remains
reproducible.

Release signing uses these GitHub Actions secrets:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

The keystore is decoded only on the Actions runner. It does not belong in the repository. A local
`assembleRelease` without the corresponding environment variables produces an unsigned release
build.

A debug-signed APK cannot update an installed official build. For an in-place candidate test, use
the signed Actions artifact and verify that its signing certificate matches the installed package
before installing it. Never uninstall or clear ForestNote merely to get around
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`: doing so can destroy an app-private library. Back up first,
then resolve the signing mismatch.

The release workflow accepts these environment variables when signing locally or in CI:

```text
RELEASE_KEYSTORE_PATH
RELEASE_KEYSTORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```
