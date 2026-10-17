# fzimra example

A small point-of-sale app that shows fzimra end to end: device registration,
sales and refunds, receipts with QR codes, fiscal days, background sync, and
every exchange with ZIMRA.

It starts on the built-in [simulator](../simulator/README.md), so it works
without a ZIMRA account. Switch to ZIMRA's test environment or production in
**Setup**.

## Run it

Open the project in Android Studio and run the `example` configuration, or:

```sh
./gradlew :example:installDebug
```

Then, in the app:

1. **Setup** → *Set up simulated device*. The app creates a device key in
   Android Keystore, sends a certificate request, installs the certificate and
   fetches the taxpayer's configuration, all against the simulator.
2. **Sell**: tap products, *Checkout*, *Charge*. The receipt is final at once:
   numbered, signed and saved on the phone. The QR code and verification code
   are on it.
3. **Traffic**: watch the day open and the receipt reach ZIMRA, with every
   request and answer.
4. **Fiscal day**: close the day and see ZIMRA confirm it. The simulator panel
   can take ZIMRA offline, lose an answer, refuse a close or flag a receipt
   Red, so you can watch fzimra recover.

On an emulator the device key is held in software; on a real phone it is in
secure hardware.

## Modes

| Mode | Use it for | Needs |
|---|---|---|
| Simulator | Learning, demos, development | Nothing |
| ZIMRA test | Certification and testing with ZIMRA | A test device ID, serial number, model and activation key from ZIMRA |
| ZIMRA production | Live fiscalisation | A production device from ZIMRA |

Each mode keeps its own device, key and receipts.

## Integrating fzimra into your POS

The example's whole use of fzimra is in
[`FiscalEngine`](src/main/kotlin/org/donnc/fzimra/example/fiscal/FiscalEngine.kt):
pick a gateway, get the terminal, show its status. The rest of the app is
UI. In your own app:

```kotlin
// Application.onCreate
val terminal = Fzimra.terminal(this, DeviceIdentity(deviceId, serialNo, modelName, modelVersion), ZimraGateway.TEST)

// once
terminal.register(activationKey)

// each sale: your cart, mapped to receipt lines
val mapper = ReceiptLineMapper<CartLine> { line ->
    ReceiptLine.sale(line.product.name, line.quantity, line.product.price, terminal.taxes.standard, line.product.hsCode)
}
terminal.sell(cart.toReceiptLines(mapper), listOf(Payment.cash()))

// end of trading
terminal.closeDay()
```

The [guide](../docs/guide.md) covers every option.

## Inside

| Package | What it holds |
|---|---|
| `fiscal` | `FiscalEngine` (the integration), `Catalogue` (the shop's own model and its mapper), `TrafficLog` (an `FdmsObserver`) |
| `settings` | Modes and device details, kept with DataStore |
| `ui` | Jetpack Compose screens with a ViewModel each, Material 3 |
