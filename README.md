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

Version 0.1.0, the first release, is not yet on a public Maven repository.
The API may still change before 1.0; every change is recorded in the
[changelog](CHANGELOG.md).

## Requirements

| | |
|---|---|
| Android | 8.0 (API level 26) or later |
| Build JDK | 17 or later |
| Android SDK | Platform 36 (the example app compiles against 37) |

## Installation

fzimra is not yet on a public Maven repository. Until it is, publish it to a
repository on your machine and depend on it from there:

```bash
./gradlew publishAllPublicationsToLocalRepository   # writes build/repository
```

```kotlin
// settings.gradle.kts of your app
dependencyResolutionManagement {
    repositories {
        maven { url = uri("/path/to/zimra-fdms-android/build/repository") }
    }
}

// build.gradle.kts of your app module
dependencies {
    implementation("org.donnc:fzimra:0.1.0")
    debugImplementation("org.donnc:fzimra-simulator:0.1.0") // optional
}
```

fzimra itself depends only on the Kotlin standard library.

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

### Connecting to ZIMRA

`FdmsClient` talks to ZIMRA's Fiscal Device Gateway for one device. Requests
other than taxpayer verification, registration and ZIMRA's signing
certificate authenticate the device with mutual TLS: the device certificate
is presented and the TLS handshake is signed by the device key inside the
Keystore. `Fzimra.client` sets this up:

```kotlin
val identity = DeviceIdentity(
    deviceId = 674473,
    serialNo = "FP9320-2026-XYZ01",
    modelName = "YourPOS",      // exactly as registered with ZIMRA
    modelVersion = "1.0",
)
val clock = ZimbabweClock()
val zimra = Fzimra.client(identity, signer, FdmsEnvironment.TEST, clock)
```

Calls block until ZIMRA answers, so make them off the main thread. Requests
that only read, and pings, are repeated after network and server errors;
requests that change something at ZIMRA are not.

#### Registering a device

```kotlin
val taxpayer = zimra.verifyTaxpayerInformation(activationKey)
// Show taxpayer.taxpayerName, taxpayerTin and branch; continue only if they are yours.

val issued = zimra.registerDevice(activationKey, DeviceCsr.create(signer, identity.serialNo, identity.deviceId))
signer.installCertificate(issued.certificatePem, identity)
```

`installCertificate` checks that the certificate is for the device key and
this device ID, and valid, then stores it with the key in the Keystore. The
same client then works for every other request. Installing replaces the key's
attestation chain, so read `signer.certificateChain` first if your server
needs it.

ZIMRA refuses every request once the certificate expires, which would stop
receipts reaching ZIMRA. Renew it well before (ZIMRA suggests a month ahead);
`signer.zimraCertificateExpiry` says when, without asking ZIMRA:

```kotlin
val renewed = zimra.issueCertificate(DeviceCsr.create(signer, identity.serialNo, identity.deviceId))
signer.installCertificate(renewed.certificatePem, identity)
```

#### Taking a device out of service

`Fzimra.deleteDeviceData(context, deviceId)` removes the device state,
records and sealing key fzimra keeps. It refuses while receipts have not
reached ZIMRA, unless you pass `discardUnsentReceipts = true`; those receipts
are then lost.

#### Working with ZIMRA

```kotlin
val config = zimra.getConfig()                   // taxes, VAT registration, limits, QR URL
val device = Fzimra.device(context, config.toDeviceConfiguration(identity.deviceId), signer, clock = clock)

val status = zimra.getStatus()                   // ZIMRA's view of the device's days and receipts
if (device.state() == null && status.lastFiscalDayNo != null) device.startFrom(status)
val problems = device.compareWith(status)        // empty when the device agrees with ZIMRA

val minutes = zimra.ping()                       // ping again after this many minutes
```

Fetch the configuration before each new fiscal day: taxes, VAT registration
and limits can change. `startFrom(status)` continues the numbering of a
device that other software used before; it refuses while ZIMRA still has a
fiscal day open for it.

`compareWith` is what catches a device state replaced by an older copy,
which seals cannot (see [Tamper evidence](#tamper-evidence)): ZIMRA has
received fiscal days or receipts the state does not know of. Do not issue
receipts while it reports problems.

Passing the same `ZimbabweClock` to the client and the device keeps receipt
dates in step with ZIMRA's clock: each answer's time corrects the clock's
offset, so receipts are never dated ahead of ZIMRA.

#### Syncing

`FiscalSync` brings ZIMRA up to date with a device: it opens fiscal days with
ZIMRA, submits receipts strictly in issuing order, closes the days that are
due, and follows each close until ZIMRA confirms or refuses it.

```kotlin
val sync = FiscalSync(device, zimra)
val report = sync.run()          // off the main thread
when (report.outcome) {
    SyncOutcome.COMPLETE -> {}   // nothing waiting
    SyncOutcome.WAITING -> {}    // ZIMRA is validating a close: run again shortly
    SyncOutcome.OFFLINE -> {}    // run again when online
    SyncOutcome.BLOCKED -> showToSupervisor(report.problems)
    SyncOutcome.BUSY -> {}       // a sync was already running
}
report.accepted                  // records of receipts ZIMRA accepted
report.closedDays                // days ZIMRA confirmed closed, with ZIMRA's signature
```

Each run does as much as it safely can and stops at the first thing it must
wait for. Sales are never held up: network calls are made without the
device's lock. Only one run per device happens at a time. Run it after each
sale, when the network returns, and periodically while the outcome is not
`COMPLETE`.

Along the way a run:

- first compares the device with ZIMRA (`compareWith`) and stops if they
  disagree;
- fetches ZIMRA's configuration before opening a day and applies it to the
  device (`FiscalDevice.updateConfiguration`); if the taxpayer's VAT
  registration changed, the current day is closed, as ZIMRA requires;
- recognises an opening or a receipt whose answer was lost, so nothing is
  sent twice;
- checks ZIMRA's signatures on accepted receipts and closed days against
  ZIMRA's signing certificate, and records the result;
- notices a day closed in ZIMRA's portal and starts a new one with the next
  sale.

`report.oldestPendingDate`, or `device.oldestUnsentReceiptDate()`, says when
the oldest receipt still waiting for ZIMRA was issued. ZIMRA's specification
sets no deadline for sending, other than before the fiscal day is closed, so
fzimra sets none either; use it to warn when the device has been unable to
reach ZIMRA for longer than you accept.

#### Background sync

fzimra can sync in the background, so receipts reach ZIMRA as soon as the
network allows, even when your app is closed. Install it once, in
`Application.onCreate`, with a way to build each device's sync:

```kotlin
class PosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FzimraBackground.install(
            onReport = { deviceId, report -> uploadToYourServer(report.accepted) },
        ) { context, deviceId ->
            FiscalSync(myDevice(context, deviceId), myClient(context, deviceId))
        }
    }
}
```

A device made with `Fzimra.device` then asks for a run after every sale and
close request. Runs use the platform's `JobScheduler`, declared in fzimra's
manifest, with no extra dependencies:

- they run only with a network connection, and survive a restart of the phone;
- while ZIMRA cannot be reached, each retry waits longer than the last;
- while ZIMRA validates a close they look again after 30 seconds, while a
  person must act every 30 minutes, and once nothing is waiting they stop
  until the next sale;
- work that arrives during a run is synced straight after it.

`SyncSchedule` changes the follow-up times; `openDay` adds a periodic check
while a day is open, to notice a close in ZIMRA's portal sooner. To sync on
your own schedule instead, pass `backgroundSync = false` to `Fzimra.device`
and call `FiscalSync.run`, or listen for new work with
`FiscalDevice.addWorkListener`.

ZIMRA also expects a device to report itself online with `ping` at the
interval ZIMRA returns while it is in use; do that from your app while it is
open.

A run stops with `BLOCKED` when a person has to act:

| Problem | What to do |
|---|---|
| The device disagrees with ZIMRA | See `compareWith`. Do not issue until resolved. |
| ZIMRA refused a receipt | Its record is `REJECTED`. Later receipts wait, since ZIMRA takes them only in order. Resolve it with ZIMRA support; the next run sends it again. |
| A day holds Grey or Red receipts | ZIMRA does not let the device close it. It must be closed in ZIMRA's portal; the next run notices. |
| ZIMRA refused a day's close | Fix the cause (`closingErrorCode`), then call `device.retryDayClose(fiscalDayNo)`. |

#### Errors

| Exception | Meaning |
|---|---|
| `FdmsNetworkException` | ZIMRA could not be reached or did not answer. Try again later. |
| `FdmsException` | ZIMRA refused the request. `errorCode` (for example `DEV01`) and `FdmsErrors.message(code)` say why; `isAuthenticationFailure` means the device certificate was refused (expired, revoked or not this device's); `isRetryable` means try again later; quote `operationId` to ZIMRA support. |
| `FdmsProtocolException` | ZIMRA's answer could not be understood. |

#### Trying it without ZIMRA

The [simulator](simulator/README.md) is a separate library with a ZIMRA
gateway that runs inside your app or tests. It applies ZIMRA's rules,
injects faults (offline, lost answers, refused closes), and shows what it
received:

```kotlin
val simulator = FdmsSimulator()
val zimra = simulator.client(identity, clientCertificate = { installedCertificate })
```

#### Other transports

`FdmsTransport` is an interface, and `HttpsTransport` uses the platform's TLS
with no extra dependencies. To trust a particular authority (for example a
test server), pass an `X509TrustManager` as `trust`; to route requests your
own way, implement `FdmsTransport`.

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
   ./gradlew build   # compile, lint and run the unit tests of every module
   ./gradlew test    # unit tests only
   ./gradlew :example:installDebug   # the example app, on a connected device
   ```

The library is written to `build/outputs/aar/`, the simulator to
`simulator/build/outputs/aar/`.

The Gradle wrapper pins the checksum of the Gradle distribution, so a modified
download is refused.

## Project structure

fzimra is one Android library, organised in two packages, with an optional
simulator library and an example app beside it:

```
src/main/kotlin/org/donnc/fzimra/
├── core/      Fiscal logic in plain Kotlin. No Android imports.
└── android/   Android platform integrations.
src/test/kotlin/org/donnc/fzimra/
               Unit tests, run on the JVM.
simulator/     Optional library: a ZIMRA gateway for development and tests.
example/       A point-of-sale app in Jetpack Compose using both.
```

The [example app](example/README.md) shows fzimra end to end and is the best
place to start an integration.

`core` must never import Android APIs, so it can be tested on the JVM and used
outside Android. A unit test (`CorePurityTest`) fails the build if it does.

## Testing

`./gradlew test` runs the unit tests on the JVM, including the simulator's
end-to-end tests (fzimra's receipts must pass every one of its checks) and the gateway client
over real mutual TLS against a local fake gateway with its own test
certificate authority. With a device or emulator connected,
`./gradlew connectedAndroidTest` runs the Android Keystore tests.

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
