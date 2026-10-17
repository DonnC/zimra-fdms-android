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
- [Usage](#usage)
- [Building from source](#building-from-source)
- [Project structure](#project-structure)
- [Testing](#testing)
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

## Usage

All amounts are `java.math.BigDecimal`. Amounts that are signed must be whole
cents: a fraction of a cent raises `FiscalValueException` instead of being
rounded silently.

### Receipt signature input

`SignatureStrings.receipt` builds the exact string a fiscal device hashes and
signs for a receipt (spec §13.2.1). `Sha256.base64` gives its hash.

```kotlin
val canonical = SignatureStrings.receipt(
    deviceId = 322,
    receiptType = ReceiptType.FISCAL_INVOICE,
    currency = "USD",
    receiptGlobalNo = 85,
    receiptDate = LocalDateTime.of(2026, 10, 17, 9, 23, 7),
    receiptTotal = BigDecimal("23.00"),
    taxes = listOf(ReceiptTax(taxId = 1, taxPercent = BigDecimal("15"),
                              taxAmount = BigDecimal("3.00"), salesAmountWithTax = BigDecimal("23.00"))),
    previousReceiptHash = null, // null for the first receipt of a fiscal day
)
val hash = Sha256.base64(canonical)
```

An exempt tax has `taxPercent = null`; a zero-rated tax has `BigDecimal.ZERO`.

### Fiscal day signature input

`SignatureStrings.fiscalDay` builds the string signed when a fiscal day is
closed (spec §13.3.1). Counters are sorted as the specification requires and
zero counters are left out.

```kotlin
val canonical = SignatureStrings.fiscalDay(
    deviceId = 322,
    fiscalDayNo = 84,
    fiscalDayDate = LocalDate.of(2026, 10, 17), // the date the day was opened
    counters = listOf(
        FiscalCounter(FiscalCounterType.SALE_BY_TAX, "USD", BigDecimal("23.00"),
                      taxId = 1, taxPercent = BigDecimal("15")),
        FiscalCounter(FiscalCounterType.BALANCE_BY_MONEY_TYPE, "USD", BigDecimal("23.00"),
                      moneyType = MoneyType.CASH),
    ),
)
```

### QR code and verification code

```kotlin
val qrData = ReceiptQr.qrData(deviceSignatureBase64)                // spec §11
val qrContent = ReceiptQr.url(qrUrl, deviceId, receiptDate.toLocalDate(), receiptGlobalNo, qrData)
val verificationCode = ReceiptQr.verificationCode(qrData)           // e.g. 4C8B-E276-6333-0417
```

`qrUrl` is the verification URL in the device's ZIMRA configuration.

### Money

`FiscalMoney` rounds half to even (`roundToCents`), converts to and from whole
cents (`toCents`, `fromCents`), and splits an amount across weights so that the
parts add up exactly (`splitProportionally`).

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

## Testing

`./gradlew test` runs the unit tests on the JVM. Signing strings, hashes and QR
codes are checked against:

- the worked examples in the ZIMRA specification (§11, §13);
- conformance vectors verified on ZIMRA's test environment
  (`src/test/resources/vectors`, see its `NOTICE.md`).

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
