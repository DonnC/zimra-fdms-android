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

### Preparing a receipt

Describe the sale with what your app already knows. fzimra works out line
totals, the tax table, the receipt total and payment amounts, applies the
signs ZIMRA requires, and checks every rule that can be checked on the device.

```kotlin
val context = ReceiptContext(
    taxes = listOf(
        TaxDefinition(1, "Standard rated 15%", BigDecimal("15")),
        TaxDefinition(2, "Zero rated 0%", BigDecimal.ZERO),
        TaxDefinition(3, "Exempt", null),
    ),
    isVatPayer = true,
)

val result = ReceiptBuilder.prepare(
    ReceiptRequest(
        type = ReceiptType.FISCAL_INVOICE,
        currency = "USD",
        invoiceNo = "INV-1029",
        lines = listOf(
            ReceiptLine.sale("Bread", quantity = 2, unitPrice = 1.15, taxId = 1, hsCode = "19059000"),
            ReceiptLine.discount("Loyalty discount", amount = 0.30, taxId = 1, hsCode = "19059000"),
        ),
        payments = listOf(Payment.cash(5)), // cash handed over; change is worked out
    ),
    context,
)

when (result) {
    is ReceiptResult.Prepared -> {
        val receipt = result.receipt // total 2.00, tax 0.26, change 3.00
    }
    is ReceiptResult.Refused -> result.problems.forEach { println("${it.field}: ${it.message} (${it.zimraCode})") }
}
```

- **Positive numbers only.** Credit note lines and refunds, and discount lines,
  become negative automatically.
- **Your number types.** `Int`, `Long`, `BigDecimal`, `Double` and `Float` are
  accepted. Floating point values are read through their shortest decimal form
  (`19.99` stays `19.99`); a value that is not exact, such as `0.1 + 0.2`, is
  refused instead of being rounded silently.
- **Unit price or line total.** Give a line by unit price (`ReceiptLine.sale`)
  or by a total your app already worked out (`ReceiptLine.saleWithTotal`).
- **Prices with or without tax.** Set `pricesIncludeTax = false` when your
  prices exclude tax; tax is then added on top.
- **Tax percentages are looked up** from the context by tax ID.
- **Payments.** `Payment.cash()` with no amount pays whatever is left of the
  total. Cash handed over beyond the total is returned as `change`.
- **Credit and debit notes** need a reason (`notes`) and the original receipt
  (`original`).
- **Every problem at once.** A refused receipt lists all its problems, each
  with the field concerned, a plain message and, when it applies, the ZIMRA
  validation code the receipt would have received.

A prepared receipt is a model only. How it is shown or printed (screen,
thermal printer, PDF) is up to your app.

### Your own item model

Your item or product class does not need to change. Write one
`ReceiptLineMapper` for it and reuse it for every sale:

```kotlin
val toLine = ReceiptLineMapper<CartItem> { item ->
    ReceiptLine.sale(item.title, item.qty, FiscalMoney.fromCents(item.priceCents),
                     taxId = taxIdFor(item.taxClass), hsCode = item.hsCode)
}

val lines = cart.items.toReceiptLines(toLine)
```

### Fiscal counters

`FiscalCounters.of(receipt)` gives the counters a prepared receipt adds to its
fiscal day, and `FiscalCounters.merge(...)` adds up counters across receipts.

### Signing primitives

All amounts are `java.math.BigDecimal`. Amounts that are signed must be whole
cents: a fraction of a cent raises `FiscalValueException` instead of being
rounded silently.

#### Receipt signature input

`SignatureStrings.receipt` builds the exact string a fiscal device hashes and
signs for a receipt. `Sha256.base64` gives its hash.

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

#### Fiscal day signature input

`SignatureStrings.fiscalDay` builds the string signed when a fiscal day is
closed. Counters are sorted in the order ZIMRA requires and
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

#### QR code and verification code

```kotlin
val qrData = ReceiptQr.qrData(deviceSignatureBase64)
val qrContent = ReceiptQr.url(qrUrl, deviceId, receiptDate.toLocalDate(), receiptGlobalNo, qrData)
val verificationCode = ReceiptQr.verificationCode(qrData)           // e.g. 4C8B-E276-6333-0417
```

`qrUrl` is the verification URL in the device's ZIMRA configuration.

#### Money

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

- the worked examples in ZIMRA's specification;
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
