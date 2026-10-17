# Security policy

fzimra handles fiscal device keys and produces tax records. Security issues are
treated as compliance issues.

## Reporting a vulnerability

Please **do not open a public issue**.

Report vulnerabilities privately, either:

- through GitHub's **Report a vulnerability** button on the repository's
  *Security* tab, or
- by email to **donnclab@gmail.com**, with "fzimra security" in the subject.

Include the affected version, steps to reproduce and the impact as you
understand it. You will receive an acknowledgement within five working days.
Fixes are released as soon as practical. Reporters are credited in the release
notes unless they prefer otherwise.

## Supported versions

| Version | Supported |
|---|---|
| Latest release | Yes |
| Older releases | No |

Until version 1.0, only the latest release receives fixes.

## Scope

In scope:

- exposure or export of device private keys;
- receipts that can be forged, altered, duplicated or re-ordered;
- weaknesses in the TLS connection to ZIMRA;
- tampering with stored fiscal state that goes undetected.

Out of scope: vulnerabilities in the host app, the device's operating system,
or ZIMRA's systems. Report those to their owners.
