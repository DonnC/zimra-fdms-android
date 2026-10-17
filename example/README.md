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
[`FiscalEngine`](src/main/kotlin/org/donnc/fzimra/example/fiscal/FiscalEngine.kt).
The screens only call it. To fiscalise your own app, do the same in four steps.

### 1. Add the libraries

```kotlin
dependencies {
    implementation(project(":"))                 // fzimra
    debugImplementation(project(":simulator"))  // only if you want the simulator
}
```

### 2. Register the device once

```kotlin
val identity = DeviceIdentity(deviceId, serialNo, modelName, modelVersion)
val signer = KeystoreSigner.getOrCreate("pos-device-$deviceId")
val zimra = Fzimra.client(identity, signer, FdmsEnvironment.TEST, clock)

val taxpayer = zimra.verifyTaxpayerInformation(activationKey)  // confirm with the user
val issued = zimra.registerDevice(activationKey, DeviceCsr.create(signer, identity.serialNo, identity.deviceId))
signer.installCertificate(issued.certificatePem, identity)
val configuration = zimra.getConfig()                           // keep it: taxes, QR URL, limits
```

### 3. Issue receipts from your own item model

```kotlin
val device = Fzimra.device(context, configuration.toDeviceConfiguration(deviceId), signer, clock = clock)

val mapper = ReceiptLineMapper<CartLine> { line ->
    ReceiptLine.sale(line.product.name, line.quantity, line.product.price, taxIds.getValue(line.product.taxClass), line.product.hsCode)
}
when (val result = device.issue(ReceiptRequest(ReceiptType.FISCAL_INVOICE, "USD", invoiceNo, cart.toReceiptLines(mapper), listOf(Payment.cash())))) {
    is IssueResult.Issued -> print(result.receipt)            // QR code, verification code, numbers
    is IssueResult.Refused -> show(result.problems)          // nothing was issued
}
```

Your product, cart and payment types stay as they are: the mapper is the
only bridge. Look ZIMRA's tax IDs up in the configuration by rate, as
[`Catalogue.taxIds`](src/main/kotlin/org/donnc/fzimra/example/fiscal/Catalogue.kt)
does. ZIMRA assigns them per taxpayer, so never hard-code them.

### 4. Install background sync in your Application

```kotlin
class PosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FzimraBackground.install(onReport = { _, report -> upload(report.accepted) }) { context, deviceId ->
            FiscalSync(myDevice(context, deviceId), myClient(context, deviceId))
        }
    }
}
```

From then on every sale reaches ZIMRA as soon as the network allows, even
when the app is closed. Fiscal days open with the first sale and close when
you call `device.requestDayClose()` (for example at the end of trading),
before ZIMRA's maximum day length, or when the global number limit or VAT
registration requires it.

### Things a POS must also do

- **Show the cashier when the fiscal day is about to end.** ZIMRA requires it;
  see `DayNotice` in the Sell screen.
- **Ping ZIMRA while the app is open**, at the interval `ping()` returns; see
  `FiscalEngine.pingWhileOpen`.
- **Renew the certificate before it expires**; see the Setup screen.
- **Keep your own copy of receipts.** fzimra keeps records of accepted
  receipts for 7 days; `onReport` gives you each accepted one to send to your
  server.
- **Tell someone when sync is `BLOCKED`.** A refused receipt or a day with
  Red receipts needs a person; `SyncReport.problems` says what.

## Inside

| Package | What it holds |
|---|---|
| `fiscal` | `FiscalEngine` (the integration), `Catalogue` (the shop's own model and its mapper), `ConfigurationStore`, `TrafficLog` |
| `settings` | Modes and device details, kept with DataStore |
| `ui` | Jetpack Compose screens with a ViewModel each, Material 3 |
