# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

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
