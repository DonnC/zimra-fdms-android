# fzimra: ZIMRA fiscalisation for Android

**fzimra** is an Android library for building ZIMRA fiscal devices: apps that
issue fiscal receipts under the Zimbabwe Revenue Authority's *Fiscal Device
Gateway API* (FDMS) v7.2.

> fzimra is an independent open-source project. It is not affiliated with,
> endorsed by or supported by ZIMRA.

## Contents

- [Project status](#project-status)
- [Requirements](#requirements)
- [Installation](#installation)
- [Building from source](#building-from-source)
- [Project structure](#project-structure)
- [Compatibility](#compatibility)
- [Contributing](#contributing)
- [Security](#security)
- [Licence](#licence)

## Project status

Pre-release. The public API has not been published and may change without
notice until version 1.0. Changes are recorded in the [changelog](CHANGELOG.md).

## Requirements

| | |
|---|---|
| Android | 8.0 (API level 26) or later |
| Build JDK | 17 or later |
| Android SDK | Platform 36 |

## Installation

fzimra is not yet published to a Maven repository. Until it is, build the
library from source and depend on the resulting AAR.

## Building from source

Android Studio is not required: any JDK 17+ works, including the one bundled
with Android Studio.

1. Point the build at your Android SDK, in `local.properties` (not committed):

   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```

2. Build and test:

   ```bash
   export JAVA_HOME=/path/to/jdk
   ./gradlew build   # compile, lint and run the unit tests
   ./gradlew test    # unit tests only
   ```

The library is written to `build/outputs/aar/`.

The Gradle wrapper pins the checksum of the Gradle distribution, so a modified
download is refused.

## Project structure

fzimra is a single Android library, organised in two packages:

```
src/main/kotlin/org/donnc/fzimra/
├── core/      Fiscal logic in plain Kotlin. No Android imports.
└── android/   Android platform integrations.
src/test/kotlin/org/donnc/fzimra/
               Unit tests, run on the JVM.
```

`core` must never import Android APIs, so it can be tested on the JVM and used
outside Android. A unit test (`CorePurityTest`) fails the build if it does.

## Compatibility

| fzimra | ZIMRA Fiscal Device Gateway API | Android |
|---|---|---|
| 0.1.x (pre-release) | v7.2 | API 26+ |

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md)
before opening a pull request. Everyone taking part is expected to follow the
[code of conduct](CODE_OF_CONDUCT.md).

## Security

fzimra handles device keys and tax records. Please report vulnerabilities
privately, as described in [SECURITY.md](SECURITY.md), and never in a public
issue.

## Licence

fzimra is released under the MIT licence. See [LICENSE](LICENSE).
