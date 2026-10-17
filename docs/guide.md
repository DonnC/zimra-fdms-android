# Guide

Everything here goes through one object, the `FiscalTerminal`.

- [Create the terminal](#create-the-terminal)
- [Register the device](#register-the-device)
- [Sell](#sell)
- [Refund](#refund)
- [End of day](#end-of-day)
- [Show the terminal's status](#show-the-terminals-status)
- [Gateways: simulator, test, production](#gateways)
- [What runs in the background](#what-runs-in-the-background)
- [Going live](#going-live)

Terminal calls touch storage or the network, so make them off the main
thread (for example `withContext(Dispatchers.IO)`). `sell`, `refund` and
`closeDay` never wait for the network.

## Create the terminal

Create it in `Application.onCreate`. The platform may start your app only to
send receipts, and the terminal must exist for that:

```kotlin
class PosApp : Application() {
    lateinit var terminal: FiscalTerminal

    override fun onCreate() {
        super.onCreate()
        terminal = Fzimra.terminal(
            context = this,
            device = DeviceIdentity(deviceId = 12345, serialNo = "SN-001", modelName = "MyPOS", modelVersion = "1.0"),
            gateway = ZimraGateway.TEST,
        )
    }
}
```

Use the device ID, serial number, model name and model version exactly as
registered with ZIMRA. Calling `Fzimra.terminal` again with the same device and
gateway returns the same terminal.

## Register the device

Once per device, with the activation key from ZIMRA:

```kotlin
val taxpayer = terminal.checkTaxpayer(activationKey)   // optional: show it and let the user confirm
terminal.register(activationKey)
```

`register` creates the device key in Android Keystore, gets ZIMRA's
certificate for it, and fetches the taxpayer's configuration. If the device
was used before, by other software or another phone, numbering continues
where ZIMRA left off. After that, `terminal.isRegistered` stays `true` across
restarts.

## Sell

```kotlin
when (val result = terminal.sell(
    lines = listOf(
        ReceiptLine.sale("Bread 700g", 2, 1.15, terminal.taxes.standard, hsCode = "19059000"),
        ReceiptLine.sale("Fresh milk", 1, 1.50, terminal.taxes.zeroRated, hsCode = "04011000"),
    ),
    payments = listOf(Payment.cash(5.00)),   // cash for the whole amount if left out
)) {
    is IssueResult.Issued -> print(result.receipt)   // final: numbered, signed and saved
    is IssueResult.Refused -> show(result.problems)  // nothing was issued
}
```

- **Taxes.** `terminal.taxes.standard`, `.zeroRated` and `.exempt` are the
  taxpayer's taxes from ZIMRA, so you never handle ZIMRA's tax IDs.
  `taxes.at(15)` finds a rate.
- **HS codes** are required for VAT-registered taxpayers: 4 or 8 digits, and
  8 for zero-rated and exempt goods.
- **Payments.** `Payment.cash()`, `.card()`, `.mobileWallet()`, or
  `Payment.of(MoneyType.BANK_TRANSFER, amount)`. A payment with no amount pays
  the rest. Cash given beyond the total comes back as `change`.
- **Prices without tax.** Pass `pricesIncludeTax = false`.
- **Business customers.** Pass `buyer = Buyer("Acme (Pvt) Ltd", tin = "2000000001", vatNumber = "220000001")`.
- **Currency** is USD unless you pass `currency = "ZWG"`.
- **Invoice numbers** are the terminal's own (`INV-12345-000001`) unless you
  pass `invoiceNo`.
- **Checking before charging.** `terminal.preview(lines, payments)` returns
  the totals and any problems without issuing anything.

What to print is in `result.receipt`: `receipt.lines`, `taxes`, `total` and
`change`, plus `globalNo`, `receiptCounter`, `fiscalDayNo` and `date`, the
`qrCode` text (render it as a QR code), and the `verificationCode`. The
taxpayer's name, TIN, VAT number and branch are in
`terminal.status().configuration`.

### Your own item model

Your products do not need to change. Map them to receipt lines in one place:

```kotlin
val mapper = ReceiptLineMapper<CartItem> { item ->
    ReceiptLine.sale(item.name, item.quantity, item.price, terminal.taxes.at(item.vatRate), item.hsCode)
}
terminal.sell(cart.items.toReceiptLines(mapper))
```

## Refund

```kotlin
terminal.refund(receipt, reason = "Damaged on delivery")                 // everything
terminal.refund(receipt, reason = "One returned", lines = listOf(line))  // part of it
```

A refund is a credit note against the original receipt. The money goes back
the way it was paid, unless you pass `payments`. The terminal checks it
against the original: same currency, within 12 months, and no more than is
left to refund.

Earlier receipts are in `terminal.receipts()`, newest first, each with its
standing at ZIMRA.

## End of day

```kotlin
terminal.closeDay()
```

The close goes to ZIMRA once every receipt of the day has reached it; the next
sale opens a new day. Days also roll over by themselves before ZIMRA's
maximum day length. Call `closeDay` when you stop trading, so each day's Z
report matches your trading day.

ZIMRA requires warning the user before the maximum day length is reached:
show a notice when `status().isDayEndingSoon`.

## Show the terminal's status

```kotlin
val status = terminal.status()
status.pendingReceipts       // waiting to reach ZIMRA
status.oldestPendingDate     // since when, to warn after a long time offline
status.day                   // the current fiscal day, its counters and receipts
status.dayEndsAt             // when it must close at the latest
status.isDayEndingSoon       // warn the user
status.problems              // what needs a person; empty when all is well
status.certificateExpiry     // renewed automatically a month before
status.isUpToDate            // nothing waiting, nothing wrong

terminal.addListener { status -> render(status) }   // after every change, on the thread that made it
```

## Gateways

| Gateway | For |
|---|---|
| `SimulatorGateway.shared(context)` | Development, demos and tests. No ZIMRA account; any 8-character activation key. From `fzimra-simulator`. |
| `ZimraGateway.TEST` | ZIMRA's test environment, with a test device from ZIMRA. |
| `ZimraGateway.PRODUCTION` | Live fiscalisation. The device key must be in secure hardware. |

Each gateway keeps its own device key, receipts and state, so switching never
mixes them. To see every exchange, pass an `observer` to `Fzimra.terminal`.

## What runs in the background

With no code from you:

- **Sending.** After every sale or close, the terminal asks the platform's job
  scheduler to send receipts once a network is available. That works when the
  app is closed and after the phone restarts. While ZIMRA cannot be reached,
  each try waits longer than the last.
- **Opening and closing days** with ZIMRA, in order, applying ZIMRA's latest
  configuration first.
- **Online reports.** While the app runs, the device is reported online at
  the interval ZIMRA asks for.
- **Certificate renewal**, a month before it expires, while the app runs and is
  online.

`terminal.sync()` sends immediately, for example from a "Sync now" button.

## Going live

1. Get a production device ID, serial number, model and activation key from
   ZIMRA, and use `ZimraGateway.PRODUCTION`.
2. Test the same flow on `ZimraGateway.TEST` first, on a real phone.
3. Print what ZIMRA requires on each receipt: the seller's details, the
   receipt numbers and date, the lines and tax table, the QR code and the
   verification code.
4. Show the day-end warning (`isDayEndingSoon`) and anything in
   `status.problems` to someone who can act on it.
5. Keep your own copy of receipts: fzimra keeps accepted receipts for 7 days.
   `terminal.addListener` or `status.lastSync?.accepted` give you each one to
   send to your server.
6. Keep fiscal data out of backups. fzimra's own storage already is; an
   older copy restored later would reuse receipt numbers.
