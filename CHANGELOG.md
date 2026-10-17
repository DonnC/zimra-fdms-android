# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Background sync on Android (`FzimraBackground`, `FzimraSyncService`):
  `JobScheduler` runs that need a network, survive restarts and back off while
  ZIMRA cannot be reached; a run follows every sale and close request on a
  device made with `Fzimra.device` (`backgroundSync`), and follows up as
  `SyncSchedule` says (`NextSync`); work arriving during a run is synced right
  after it. The library declares the job service and the
  `RECEIVE_BOOT_COMPLETED` permission.
- `FiscalDevice.addWorkListener`: told of each sale, close request, retried
  close and VAT-driven close.
- Syncing with ZIMRA (`FiscalSync`): opens fiscal days with ZIMRA, submits
  receipts strictly in order, closes due days with the device's signed
  counters and follows each close to ZIMRA's confirmation or refusal; each
  run reports a `SyncOutcome` (`COMPLETE`, `WAITING`, `OFFLINE`, `BLOCKED`,
  `BUSY`) and what it did (`SyncReport`, `ClosedDay`):
  - compares the device with ZIMRA before anything is sent;
  - applies ZIMRA's configuration before opening a day; a changed VAT
    registration closes the current day (`DayCloseReason.VAT_REGISTRATION_CHANGED`);
  - recognises lost answers to openings, receipts and closes, so nothing is
    sent twice;
  - checks ZIMRA's signatures on receipts and closed days
    (`ReceiptRecord.zimraSignatureVerified`, `ClosedDay.zimraSignatureVerified`);
  - stops at a refused receipt, a day with Grey or Red receipts, or a refused
    close, and notices a day closed in ZIMRA's portal;
  - never holds up issuing; one run per device at a time.
- `FdmsClient.openDay`, `submitReceipt` and `closeDay`, with receipts and
  counters in ZIMRA's JSON; `ZimraStatus` carries ZIMRA's close details.
- `FiscalDevice.updateConfiguration` and `retryDayClose`.
- Day and record fields for sync: `FiscalDayState.zimraOpened`,
  `blockingReceipts`, `closeSignature`, `closingErrorCode`;
  `ReceiptRecord.zimraSignature`.
- Tests against an in-memory gateway that checks every receipt's signature
  and sequence from the JSON it receives, adds up its own counters, checks
  the close against them and signs what it accepts: full days, offline and
  lost answers, queued days, restarts, Grey and Red receipts, portal closes,
  refused receipts and closes, configuration and VAT changes, and issuing
  during a sync.
- Connecting to ZIMRA:
  - `FdmsClient`: taxpayer verification, device registration, certificate
    renewal, configuration, status, ping and ZIMRA's signing certificate,
    with ZIMRA's headers, error details (`FdmsException`, `FdmsErrors`) and
    retries for reads and pings only;
  - `HttpsTransport`: HTTPS with mutual TLS on the platform's TLS stack and no
    extra dependencies; `FdmsTransport` for other transports;
  - `KeystoreSigner.installCertificate`, `hasZimraCertificate` and
    `keyManager()`: the ZIMRA certificate is checked and kept with the key,
    and the TLS handshake is signed inside the Keystore;
  - `Fzimra.client` builds a client for a Keystore device key;
  - the library declares the `INTERNET` permission;
  - `DeviceCertificate`: reads ZIMRA's certificate and checks it is for the
    device key and device ID, and valid;
  - `ZimraConfiguration.toDeviceConfiguration`, and a `ZimbabweClock` kept in
    step with ZIMRA's clock from each answer (`adjustTo`).
- `FiscalDevice.compareWith(status)` compares the device state with ZIMRA's
  record of its days and receipts, which catches a state replaced by an older
  sealed copy; `FiscalDevice.startFrom(status)` continues numbering from
  ZIMRA's figures.
- Tests: every request's form and every answer's reading, errors and retries,
  the clock, mutual TLS against a local fake gateway (registration, device
  requests, refused certificates, untrusted servers, timeouts), certificate
  checks and comparisons with ZIMRA; on-device certificate tests.
- Tamper evidence for stored data:
  - `Sealer`, and `KeystoreSealer`: an HMAC-SHA256 key in Android Keystore
    (StrongBox when available) that seals every stored device state and
    receipt record (`FiscalSnapshot.seal`, `ReceiptRecord.seal`); seals cover
    the data, not its storage, so app-provided stores are protected too;
  - every read checks the seal; the first read of each `FiscalDevice` also
    checks every unsent receipt's hash and signature, the links between
    consecutive receipts, and the device state's agreement with them;
  - data that fails is never used: `FiscalIntegrityException` lists what was
    found; tampered records are not used to check credit and debit notes;
  - `Fzimra.device` creates the sealing key (`Fzimra.sealAlias`), held at
    least as securely as the device key, unless the app passes its own.
- Tests: edited, unsealed and foreign-sealed state, a different sealing key,
  seals of one kind passing for another, edited receipts under a valid seal,
  wrong signing keys, missing receipts and days, state that disagrees with its
  receipts, and edited records; on-device sealing key and tamper tests.
- Global numbering (`GlobalNumbering`, `GlobalNumberReset`): choose when the
  global number starts again at 1: near its limit (the default) or every new
  fiscal day. A restart only happens with the first receipt of a new fiscal
  day. There is no policy that stops issuing: if the limit (by default
  `Int.MAX_VALUE`) is reached during a day, under either policy, the day is
  closed and the next receipt starts a new day at 1.
- `DayCloseReason` on every closing fiscal day: `REQUESTED`,
  `MAX_DAY_LENGTH` or `GLOBAL_NUMBER_LIMIT`.
- Credit and debit notes are checked against the original receipt: currency,
  age, taxes and, for credit notes, the amount left to credit after earlier
  notes. The original comes from the device's records, found by device
  numbers or ZIMRA receipt ID, or from `ReceiptRequest.originalDetails`
  (`OriginalReceiptDetails`). Without either, the note is issued with a
  warning (`IssueResult.Issued.warnings`).
- Tests: the original-receipt rules, every numbering policy, restarts never
  mid-day, and notes checked against recorded and given originals.
- Issuing receipts:
  - `FiscalDevice.issue` prepares, numbers, chains, dates, signs and saves a
    receipt in one step, and returns `IssueResult.Issued` or `Refused`;
  - fiscal days open with the first sale and roll over before ZIMRA's
    maximum day length or after `requestDayClose()`; closing days are kept
    with their counters (`DeviceState.closingDays`);
  - receipt counter, global number and hash chain follow on without gaps; a
    refused, unsigned or unsaved receipt uses no number;
  - receipt dates in Zimbabwe time (`ZimbabweClock`, with an offset towards
    ZIMRA's clock), never earlier than the previous receipt;
  - `startFrom` continues numbering from another system;
  - issuing is serialised per device ID across the app;
  - `Fzimra.device` sets up a device with fzimra's own stores unless the app
    provides its own; `DeviceConfiguration` holds the device's ZIMRA settings.
- Tests: numbering, chaining, dates, refusals and failures that use no
  number, day rollover and close requests, restarts, two instances for one
  device, and concurrent issuing.
- Storage:
  - `FiscalStateStore`: a device's numbering state and the receipts ZIMRA
    has not yet accepted, saved as one atomic `FiscalSnapshot`; never pruned;
  - `ReceiptRecordStore` and its read-only view `ReceiptRecords`: the history
    of issued receipts (`ReceiptRecord`) and their standing with ZIMRA;
  - models for the device state, fiscal days, issued receipts and ZIMRA's
    findings;
  - default stores used when the app provides none: private no-backup files,
    written atomically; records of accepted receipts removed after 7 days,
    pending and rejected ones kept.
- Tests: contract tests every store implementation must pass, retention
  rules, and exact round trips of stored data.
- Keys and signing:
  - `Signer` interface and `SignatureAlgorithm` (ECDSA P-256 or RSA, SHA-256);
  - `FiscalSignature.sign` and `verify`: every signature is verified against
    the device's public key before it is returned, so a faulty signer or a
    signature in a format ZIMRA rejects is caught on the device;
  - `DeviceKeys`: only EC P-256 and RSA keys of 2048 bits or more are accepted;
  - `DeviceCsr`: the device's certificate signing request (PKCS #10, PEM),
    with the subject ZIMRA expects;
  - `KeystoreSigner`: a non-exportable EC P-256 key in Android Keystore,
    StrongBox when available, with key attestation, security level, and
    refusal of software-only keys by default.
- Tests: signatures verified against the conformance vectors' public key,
  certificate requests checked with an independent parser, wrong key types
  and wrong signature formats refused; on-device Keystore tests.
- Receipts in `org.donnc.fzimra.core`:
  - `ReceiptRequest`, `ReceiptLine`, `Payment`, `Buyer` and `OriginalReceipt`
    describe a sale, a credit note or a debit note with positive numbers in
    the app's own number types;
  - `ReceiptLineMapper` and `toReceiptLines` turn the app's own item model
    into receipt lines without changing it;
  - `ReceiptBuilder.prepare` works out line totals, exact unit prices, the tax
    table (per tax, half to even), the receipt total, payment amounts and cash
    change, applies credit note and discount signs, and returns a
    `PreparedReceipt` or every problem found (`ReceiptProblem`, with the ZIMRA
    validation code where one applies);
  - checks for currency, lines, quantities, prices, taxes in force, VAT for
    non-VAT payers, HS codes, totals, payments, buyer data and credit and
    debit note references;
  - `FiscalCounters`: the counters a receipt adds to its fiscal day, merging
    and totals.
- Tests for every rule, the computations and the conformance vectors' tax
  tables, including tax-exclusive receipts.
- Fiscal primitives in `org.donnc.fzimra.core`:
  - `FiscalMoney`: `BigDecimal` arithmetic with half-even rounding, exact
    conversion to whole cents (fractions of a cent are refused), and
    proportional splitting that sums exactly;
  - `SignatureStrings`: the strings a device signs for receipts and fiscal
    days, the strings ZIMRA signs for them, tax-percent and date formatting,
    and counter ordering;
  - `ReceiptType`, `MoneyType` and `FiscalCounterType` in ZIMRA's order; `ReceiptTax` and `FiscalCounter` with input validation;
  - `Sha256` and `ReceiptQr` (QR data, QR content and verification code).
- Tests against the worked examples in ZIMRA's specification and the zimra-fdms
  conformance vectors verified on ZIMRA's test environment.
- Android library project (`org.donnc.fzimra`), minimum Android 8.0 (API 26).
- `core` and `android` packages, with a unit test that keeps `core` free of
  Android imports.
- Gradle wrapper with a pinned distribution checksum.
- README, contributing guide, security policy, code of conduct and MIT licence.

### Changed

- `FiscalDevice.configuration` can be updated (`updateConfiguration`).
- `BuyerAddress` and `BuyerContacts` are now `Address` and `Contacts`, also
  used for the taxpayer's branch.
- `FiscalDevice` takes a `sealer`.
- `ReceiptRecords.find` takes the fiscal day number as well as the global
  number, since global numbers may repeat across fiscal days.
