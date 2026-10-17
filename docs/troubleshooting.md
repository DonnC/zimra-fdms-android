# Troubleshooting

Start with `terminal.status()`: `problems` says what needs a person, and
`lastSync` what the last attempt to reach ZIMRA did.

## Receipts are waiting and not reaching ZIMRA

`status.pendingReceipts` stays above zero.

- **No network, or ZIMRA unavailable.** `lastSync.outcome` is `OFFLINE`.
  Nothing to do: fzimra keeps trying, waiting longer each time, and sends
  everything in order once ZIMRA answers. Sales continue meanwhile.
- **A receipt was refused.** `lastSync.outcome` is `BLOCKED`, `problems` names
  the receipt and ZIMRA's error code, and its record is `REJECTED`. ZIMRA takes
  receipts only in order, so later ones wait. Resolve the cause with ZIMRA,
  then `terminal.sync()`.
- **The terminal is not created at app start.** Background sending needs the
  terminal to exist: create it in `Application.onCreate`.

## The fiscal day will not close

- **"Has receipt(s) ZIMRA marked Grey or Red".** ZIMRA does not let a device
  close such a day. Close it in ZIMRA's portal; fzimra notices on its next
  sync and moves on.
- **"ZIMRA refused to close fiscal day…"** with a code:
  - `CountersMismatch`: the day's totals disagree with what ZIMRA received;
  - `BadCertificateSignature`: the close's signature did not verify;
  - `MissingReceipts`: ZIMRA is missing receipts of the day.

  Fix the cause, then `terminal.retryDayClose()`.
- **Waiting.** The close was sent and ZIMRA is still validating it
  (`lastSync.outcome` is `WAITING`). It finishes on its own.

## Registration fails

| Error | Meaning |
|---|---|
| `DEV02` | The activation key is wrong. It is 8 characters, from ZIMRA's device portal. |
| `DEV03` | The certificate request was refused: check the device ID and serial number. |
| `DEV06` | The model name and version do not match what ZIMRA registered. |
| `DEV01` | The device is not active at ZIMRA. |
| `KeystoreUnavailableException` | The phone cannot hold the key in secure hardware. Production requires it. Emulators can use `ZimraGateway.TEST` or the simulator. |

## ZIMRA refuses the device (HTTP 401)

`FdmsException.isAuthenticationFailure`: the certificate expired, was
revoked, or is not this device's. fzimra renews it a month before expiry
while the app runs and is online. A phone that was off longer than that must
register again.

## "The device state is older than what ZIMRA received"

The fiscal data on this phone was restored from an older copy, or the device
was used on another phone. Do not issue receipts on it. On the phone that
should use the device, remove it (`terminal.unregister(discardUnsentReceipts = true)`)
and register it again: numbering continues from ZIMRA's figures.

## "Fiscal data failed its integrity checks"

Stored fiscal data was changed outside fzimra, or its sealing key was lost.
fzimra refuses to use it. Restore the phone's real data if you can; otherwise
remove the device (`terminal.unregister(discardUnsentReceipts = true)`) and
register it again.

## Receipts are flagged

A finding on an accepted receipt is in its record's `validations`:

- **Yellow** findings (for example `RCPT031`, a receipt dated in the future)
  are warnings.
- **Red and Grey** findings block the day's close.

fzimra checks ZIMRA's rules before signing, so findings usually mean the
phone's clock or ZIMRA's configuration changed. fzimra corrects the clock
from ZIMRA's answers.

## Testing without ZIMRA

Use `SimulatorGateway`. Its `simulator.faults` can make it go offline, lose
answers, refuse closes or flag receipts, so you can check how your app shows
each case. See the [simulator](../simulator/README.md).
