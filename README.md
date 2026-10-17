# fzimra

ZIMRA fiscalisation for Android. Sign receipts on the device, and send them
to ZIMRA in the background, in order, even after time offline.

```kotlin
val terminal = Fzimra.terminal(context, DeviceIdentity(12345, "SN-001", "MyPOS", "1.0"), ZimraGateway.TEST)

terminal.register(activationKey)                                    // once per device

val result = terminal.sell(listOf(
    ReceiptLine.sale("Bread", 2, 1.15, terminal.taxes.standard, hsCode = "19059000"),
))
terminal.closeDay()                                                 // end of trading
```

That is the whole integration. The terminal handles the rest itself:

- the device key, held in Android Keystore, and ZIMRA's certificate for it;
- ZIMRA's configuration, kept for offline use and refreshed before each day;
- receipt numbering, hash chain, signatures, QR codes and invoice numbers;
- fiscal days, opened and closed with ZIMRA as needed;
- sending receipts in order as soon as the network allows, in the background;
- pinging ZIMRA and renewing the certificate before it expires.

> fzimra is an independent open-source project, not affiliated with ZIMRA.

## Try it in two minutes

Run the [example app](example/README.md) (`./gradlew :example:installDebug`)
and tap **Set up simulated device**. It uses the built-in
[simulator](simulator/README.md), so no ZIMRA account is needed, and its
**Traffic** tab shows every exchange.

## Install

```kotlin
dependencies {
    implementation("org.donnc:fzimra:0.1.0")
    debugImplementation("org.donnc:fzimra-simulator:0.1.0")   // optional
}
```

fzimra is not on a public repository yet:
`./gradlew publishAllPublicationsToLocalRepository` publishes it to
`build/repository`. Add that folder as a Maven repository in your app.

Requires Android 8.0 (API 26) or later. fzimra depends only on the Kotlin
standard library.

## Documentation

| | |
|---|---|
| [Guide](docs/guide.md) | Everything the terminal does: registering, selling, refunds, end of day, status, gateways, going live |
| [Troubleshooting](docs/troubleshooting.md) | What to do when something is stuck or refused |
| [Advanced](docs/advanced.md) | The lower-level API, for custom storage or flows |
| [Example app](example/README.md) | A Jetpack Compose point of sale built on fzimra |
| [Simulator](simulator/README.md) | ZIMRA's gateway inside your app or tests |

## Status

Version 0.1.0, implementing ZIMRA's Fiscal Device Gateway API v7.2 in online
mode. The API may change before 1.0; see the [changelog](CHANGELOG.md).

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md). Report vulnerabilities privately, as
described in [SECURITY.md](SECURITY.md).

## Licence

MIT. See [LICENSE](LICENSE).
