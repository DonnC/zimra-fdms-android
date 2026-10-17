# fzimra simulator

A ZIMRA Fiscal Device Gateway that runs inside your app or tests. Register a
device, open and close fiscal days and submit receipts without a ZIMRA
account, and see what ZIMRA would say about each one.

It is a development aid, not ZIMRA. Before going live, test against ZIMRA's
test environment.

## Installation

The simulator is a separate library, so it never ships in an app by accident.
Depend on it only where you need it, for example in a debug build or tests:

```kotlin
debugImplementation(project(":simulator"))   // or the published artifact
testImplementation(project(":simulator"))
```

## Usage

```kotlin
val simulator = FdmsSimulator(store = SimulatorStore.InFile(File(context.noBackupFilesDir, "simulator.json")))
val zimra = simulator.client(identity, clientCertificate = { signer.certificateChain.firstOrNull()?.takeIf { signer.hasZimraCertificate } })
```

`zimra` is an ordinary `FdmsClient`: register with any eight-character
activation key, then use it exactly as you would with ZIMRA, including with
`FiscalSync` and background sync. `clientCertificate` stands in for mutual
TLS: device requests must present a certificate the simulator issued to that
device.

## What it checks

Like ZIMRA, it accepts every receipt it can read and reports problems as
validation findings. It checks:

- receipt counter, global number and hash chain, and the device's signature
  over ZIMRA's receipt string;
- receipt dates against the previous receipt, the day's opening, the end of
  the day and its own clock;
- lines, totals, taxes, payments and their signs, tax rates, HS codes, VAT
  registration and buyer details;
- credit and debit notes against their original receipts;
- a day's close against its own counters, the receipt count and the device's
  signature, a little after the close is sent, as ZIMRA validates closes.

Also as ZIMRA does:
- it reports its status with the number of the latest-dated receipt, not the
  highest;
- it answers a receipt sent again with the one it already has;
- it refuses to close a day holding Grey or Red receipts;
- it signs every accepted receipt and closed day.

## Faults

`simulator.faults` injects what a device must survive. Each fault is used up
as it fires:

| Fault | Effect |
|---|---|
| `offline` | Every request fails until set back. |
| `dropConnections` | The next requests fail as dropped connections. |
| `serverErrors` | The next requests are answered with HTTP 503. |
| `dropAfterSubmit` | The next receipts are accepted but the answer is lost. |
| `dateSkew` | The time on each answer is off by this much. |
| `flagNextReceipt` | The next receipt gets a finding, such as `"RCPT025" to "Red"`. |
| `refuseNextClose` | The next close fails with this code. |
| `refuseCloseAsReopened` | Refused closes are reported as the day opened again with an error code, as some gateways do. |

`closeInPortal(deviceId)` closes the open day as a ZIMRA officer would in the
portal. `device(deviceId)` shows what the simulator holds: days, receipts and
findings.

## State

The default `SimulatorStore.InMemory()` starts fresh each time. To keep the
simulator's state with the device's, use `SimulatorStore.InFile`.

## Credits

The behaviour follows the local FDMS of
[zimra-fdms](https://github.com/munashe-chivandire/zimra-fdms), which replays
what was observed on ZIMRA's test environment. See [NOTICE.md](NOTICE.md).
