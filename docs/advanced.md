# Advanced

`FiscalTerminal` covers what a point of sale needs. Underneath it are the
classes it is built from, for apps that need their own storage, their own
scheduling or their own flows. Use them only when the terminal does not fit.

| Class | Does |
|---|---|
| `FiscalDevice` | Issues receipts: numbering, dating, chaining, signing, saving. No network. |
| `FiscalSync` | Brings ZIMRA up to date with a device: days, receipts, closes. |
| `FdmsClient` | Every request to ZIMRA's gateway. |
| `KeystoreSigner` | The device key in Android Keystore, and its ZIMRA certificate. |
| `ReceiptBuilder` | Prices and checks a receipt request, without issuing it. |

`terminal.device` and `terminal.client` give the terminal's own instances.

## A device without the terminal

```kotlin
val signer = KeystoreSigner.getOrCreate("pos-device-12345")
val zimra = Fzimra.client(identity, signer, FdmsEnvironment.TEST)

val issued = zimra.registerDevice(activationKey, DeviceCsr.create(signer, identity.serialNo, identity.deviceId))
signer.installCertificate(issued.certificatePem, identity)
val configuration = zimra.getConfig()   // keep it: the device needs it to issue

val device = Fzimra.device(context, configuration.toDeviceConfiguration(identity.deviceId), signer)
device.issue(request)
FiscalSync(device, zimra).run()          // when you choose; or let background sync do it
```

You then keep the configuration yourself, refresh it before each day
(`FiscalSync` does when it opens a day), renew the certificate with
`issueCertificate` before `signer.zimraCertificateExpiry`, and `ping` at the
interval ZIMRA returns.

## Your own storage

Implement `FiscalStateStore` (numbering state and unsent receipts) and
`ReceiptRecordStore` (receipt history), and pass them to `Fzimra.device` or
`FiscalTerminal`. A state store must:

- write atomically: a read returns the new snapshot or the previous one, never
  a mix, even after a crash;
- write durably, before `write` returns;
- never discard snapshots, nor restore an older one: an older copy makes the
  device reuse numbers ZIMRA already has. Keep it out of backups.

Keep the `seal` field of every snapshot and record exactly as given: fzimra
refuses data whose seal does not match.

## Background sync for your own flows

Devices made with `Fzimra.device` ask for background runs after each sale.
Tell the scheduler how to build their sync once, in `Application.onCreate`:

```kotlin
FzimraBackground.install(onReport = { deviceId, report -> /* … */ }) { context, deviceId ->
    FiscalSync(myDevice(context, deviceId), myClient(context, deviceId))
}
```

`SyncSchedule` sets how soon runs follow up on each outcome. Pass
`backgroundSync = false` to `Fzimra.device` to schedule everything yourself.

## Global numbering

`GlobalNumbering(GlobalNumberReset.EVERY_FISCAL_DAY)` starts the global receipt
number at 1 in every new fiscal day; the default `NEAR_LIMIT` continues it
until it nears its limit. A restart only ever happens with a day's first
receipt, as ZIMRA requires. Pass it as `numbering` to `Fzimra.terminal` or
`Fzimra.device`.

## Checking against ZIMRA

`device.compareWith(zimra.getStatus())` lists what disagrees between the
device and ZIMRA, for example after a restore from an older copy.
`FiscalSync` runs it before every sync. `device.startFrom(status)` continues
numbering that other software started.

## Security

- **Device key.** The device key is created in Android Keystore and cannot be
  exported. StrongBox is used when the phone has it. Production refuses keys
  held only in software.
- **Sealing.** Stored state and records are sealed with a second Keystore key.
  Data edited outside fzimra is refused (`FiscalIntegrityException`).
- **Receipt checks.** Every unsent receipt's signature and chain is checked
  before use.
- **ZIMRA's signatures.** These are checked on accepted receipts and closed
  days (`zimraSignatureVerified`).
- **Backups.** fzimra's storage is excluded from backups.
