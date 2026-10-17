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

### Issuing receipts

`FiscalDevice` issues receipts: each one is numbered, chained to the previous
receipt, dated, signed and saved before `issue` returns.

```kotlin
val configuration = DeviceConfiguration(
    deviceId = 674473,
    qrUrl = "https://fdmstest.zimra.co.zw",
    taxes = taxes,          // the taxes in the device's ZIMRA configuration
    isVatPayer = true,
    dayMaxHours = 24,
)
val device = Fzimra.device(context, configuration, signer = KeystoreSigner.getOrCreate("fzimra-device-674473"))

when (val result = device.issue(request)) {
    is IssueResult.Issued -> {
        val receipt = result.receipt
        result.warnings           // checks that could not be made, if any
        receipt.globalNo; receipt.receiptCounter; receipt.fiscalDayNo; receipt.date
        receipt.qrCode            // print as a QR code
        receipt.verificationCode  // print as text, e.g. 4C8B-E276-6333-0417
    }
    is IssueResult.Refused -> showProblems(result.problems) // nothing was issued
}
```

fzimra fills in what can be derived:

- **Fiscal day.** A day opens with the first sale. When ZIMRA's maximum day
  length is near (an hour before, by default), or a close was requested with
  `requestDayClose()`, the next sale starts a new day; the previous day is
  kept until its close is confirmed.
- **Numbers.** The receipt counter (per day) and the global number follow on
  without gaps. A receipt that is refused, or that cannot be signed or saved,
  uses no number. When the global number starts again at 1 is up to you (see
  below).
- **Date.** Receipts are dated in Zimbabwe time, whatever the phone's time
  zone, and always after the previous receipt.
- **Chain, signature, QR code, verification code and fiscal counters.**

Issued receipts wait in `pendingReceipts()` until ZIMRA accepts them. If the
device was used by another system before, call `startFrom(lastGlobalNo,
lastFiscalDayNo)` before its first receipt so the numbering continues.

One device must issue from one place: fzimra serialises issuing per device ID
within the app, and re-reads the saved state for every receipt.

#### Global numbering

The global number runs on across fiscal days. ZIMRA allows it to start again
at 1 only with the first receipt of a fiscal day, and rejects a restart in the
middle of one, so fzimra only ever restarts it when a new day begins. Choose
when with `numbering`:

```kotlin
Fzimra.device(context, configuration, signer, numbering = GlobalNumbering(GlobalNumberReset.EVERY_FISCAL_DAY))
```

| `GlobalNumberReset` | The global number starts again at 1 |
|---|---|
| `NEAR_LIMIT` (default) | in the first new fiscal day after it passes 90% of `limit`, so normally on an ordinary day boundary. |
| `EVERY_FISCAL_DAY` | in every new fiscal day. |

`limit` defaults to `Int.MAX_VALUE` (2,147,483,647), the largest number a
receipt can carry, so by default numbering simply continues: at 100,000
receipts a day that lasts about 59 years. Set it lower to restart earlier, for
example to keep printed numbers short.

There is no option to stop issuing at the limit: the only number ZIMRA accepts
after it is 1 in a new fiscal day. So if `limit` is reached during a day,
under either policy, fzimra closes that day and the next sale opens a new one
starting at 1. ZIMRA allows a day to be closed at any time and sets no
minimum length. The closing day carries `closeReason =
DayCloseReason.GLOBAL_NUMBER_LIMIT`; tell your users, since a Z report then
appears during trading.

#### Fiscal days and trading hours

ZIMRA does not tie fiscal days to calendar days or business hours. A day
lasts from its opening to its close, at most `dayMaxHours`; a day may run past
midnight, and a date may have several days. Fit days to your trading hours by
calling `requestDayClose()` when you close for the day: the next sale opens
the next fiscal day. Every closing day in `DeviceState.closingDays` says why
it closed (`REQUESTED`, `MAX_DAY_LENGTH` or `GLOBAL_NUMBER_LIMIT`).

#### Credit and debit notes

A note is checked against the receipt it corrects: same currency, an
original at most 12 months old, only taxes the original had, and, for a
credit note, no more than is left to credit after earlier notes.

When the original was issued on this device and its record is still kept,
fzimra finds it by `OriginalReceipt.ByDevice` or, once ZIMRA has accepted it,
by `OriginalReceipt.ById`, and counts the earlier notes against it itself.
Otherwise pass its details:

```kotlin
val request = ReceiptRequest(
    type = ReceiptType.CREDIT_NOTE,
    // ...
    notes = "Goods returned",
    original = OriginalReceipt.ById(zimraReceiptId),
    originalDetails = OriginalReceiptDetails(
        currency = "USD",
        date = originalDate,
        total = BigDecimal("115.00"),
        taxes = originalTaxes,
        previousCredits = BigDecimal("15.00"), // earlier credit notes against it
    ),
)
```

Without a record or details the note is still issued, with a warning in
`IssueResult.Issued.warnings`, because the checks could not be made. ZIMRA
then makes them when the note is submitted.

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
  (`original`). Issuing also checks them against the original (see above).
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

### The device key

The device key is an EC P-256 key generated inside Android Keystore. It cannot
be exported: your app and fzimra only see its public key and the signatures
it makes. StrongBox is used when the device supports the key's requirements;
otherwise the trusted execution environment. A key held only in software is
refused unless you allow it (for emulators).

```kotlin
val signer = KeystoreSigner.getOrCreate(
    alias = "fzimra-device-674473",
    attestationChallenge = challengeFromYourServer, // optional, used when the key is created
)
signer.securityLevel     // STRONGBOX, TRUSTED_ENVIRONMENT, ...
signer.certificateChain  // key attestation chain, for your server to check
```

One alias holds one device key. Deleting it (`KeystoreSigner.delete`) is
irreversible: the device must then be registered with ZIMRA again.

### Certificate request

ZIMRA issues the device certificate from a certificate signing request made
with the device key:

```kotlin
val csrPem = DeviceCsr.create(signer, serialNo = "FP9320-2026-XYZ01", deviceId = 674473)
```

### Signing

`FiscalSignature.sign` signs a string built by `SignatureStrings` and returns
its hash and signature. Every signature is verified against the device's
public key before it is returned, so a faulty signer fails on the device
rather than at ZIMRA.

```kotlin
val signature = FiscalSignature.sign(signer, canonical)
signature.hash       // Base64 SHA-256 of the signed text
signature.signature  // Base64 signature
```

`Signer` is an interface: implement it only to use another secure key store.

### Storage

fzimra keeps two kinds of data, through two interfaces your app may
implement:

- `FiscalStateStore` holds what a fiscal device must never lose: its
  numbering, its current fiscal day and the receipts ZIMRA has not yet
  accepted. It is written as one snapshot, atomically, and never pruned.
- `ReceiptRecordStore` holds the history of issued receipts and their
  standing with ZIMRA, for display and lookup.

If your app provides neither, fzimra keeps both itself, in the app's private
no-backup storage: device state in one small file per device, and records as
one small file per receipt. Records of receipts ZIMRA has accepted are removed
after 7 days; records of receipts that are pending or rejected are kept.
Through fzimra, records are read-only (`ReceiptRecords`).

Fiscal data is never backed up. Restoring an older copy of the device state
would make the device reuse receipt numbers ZIMRA has already received. If
you implement `FiscalStateStore` yourself, keep its data out of backups too.

#### Tamper evidence

Everything fzimra stores is sealed: each device state and each receipt record
carries a `seal`, an HMAC-SHA256 tag made with a key in Android Keystore
(`KeystoreSealer`, StrongBox when available). The app cannot read that key, so
it cannot forge a seal over edited data. Seals are computed over the data
itself, not its storage, so they protect your own stores as well as fzimra's.
Keep the `seal` field as given when you store a snapshot or record.

On every read, fzimra checks the seal. The first time each `FiscalDevice`
instance reads the state, it also checks every receipt not yet accepted by
ZIMRA: its hash and its signature by the device key, the counters, global
numbers, hash links and dates between consecutive receipts, and that the
device state agrees with them. Data that fails is never used:

```kotlin
try {
    device.issue(request)
} catch (e: FiscalIntegrityException) {
    e.problems // what was found; nothing was issued
}
```

Reading records that fail (`device.records`) throws the same exception. A
credit note whose original can only be found in a tampered record is still
issued, with a warning, as if the original were not recorded.

`Fzimra.device` creates the sealing key under `Fzimra.sealAlias(deviceId)`,
held at least as securely as the device key. Keep it as long as the device key:
without it, stored state can no longer be checked and fzimra refuses it. Both
keys, and fzimra's own stores, go when the app is uninstalled or its data is
cleared. To seal another way, pass your own `Sealer`.

What a seal cannot show is a **rollback**: an older copy of the state, sealed
by fzimra at the time, restored in place of the current one. A phone has no
reliable way to tell. This is why fiscal data is kept out of backups, and why
fzimra will compare the device state with ZIMRA's own record of the device's
last fiscal day and receipt before it opens a day with ZIMRA.

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

`./gradlew test` runs the unit tests on the JVM. With a device or emulator
connected, `./gradlew connectedAndroidTest` runs the Android Keystore tests.

Signing strings, hashes, signatures and QR codes are checked against:

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
