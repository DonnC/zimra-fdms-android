# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

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
