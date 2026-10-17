package org.donnc.fzimra.example.ui.sell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.donnc.fzimra.core.Buyer
import org.donnc.fzimra.core.IssueResult
import org.donnc.fzimra.core.IssuedReceipt
import org.donnc.fzimra.core.ReceiptProblem
import org.donnc.fzimra.core.ReceiptResult
import org.donnc.fzimra.example.fiscal.CartLine
import org.donnc.fzimra.example.fiscal.FiscalEngine
import org.donnc.fzimra.example.fiscal.PaymentChoice
import org.donnc.fzimra.example.fiscal.Product
import org.donnc.fzimra.example.fiscal.Sale
import org.donnc.fzimra.example.ui.friendly

data class SellUi(
    val cart: List<CartLine> = emptyList(),
    val currency: String = "USD",
    val payment: PaymentChoice = PaymentChoice.CASH,
    val cashTendered: String = "",
    val businessCustomer: Boolean = false,
    val buyerName: String = "",
    val buyerTin: String = "",
    val buyerVat: String = "",
    val preview: ReceiptResult? = null,
    val issuing: Boolean = false,
    val issued: IssuedReceipt? = null,
    val warnings: List<ReceiptProblem> = emptyList(),
    val error: String? = null,
) {
    val itemCount: Int get() = cart.sumOf { it.quantity }
    fun quantityOf(product: Product): Int = cart.firstOrNull { it.product.sku == product.sku }?.quantity ?: 0
}

class SellViewModel(private val engine: FiscalEngine) : ViewModel() {
    private val mutable = MutableStateFlow(SellUi())
    val ui: StateFlow<SellUi> = mutable

    fun add(product: Product) = change { ui ->
        val existing = ui.quantityOf(product)
        ui.copy(cart = if (existing == 0) ui.cart + CartLine(product, 1) else ui.cart.map { if (it.product.sku == product.sku) it.copy(quantity = it.quantity + 1) else it })
    }

    fun remove(product: Product) = change { ui ->
        ui.copy(cart = ui.cart.mapNotNull { if (it.product.sku != product.sku) it else it.copy(quantity = it.quantity - 1).takeIf { l -> l.quantity > 0 } })
    }

    fun clear() = change { SellUi(currency = it.currency) }

    fun currency(code: String) = change { it.copy(currency = code) }
    fun payment(choice: PaymentChoice) = change { it.copy(payment = choice, cashTendered = if (choice == PaymentChoice.CASH) it.cashTendered else "") }
    fun cashTendered(text: String) = change { it.copy(cashTendered = text.filter { c -> c.isDigit() || c == '.' }.take(10)) }
    fun businessCustomer(on: Boolean) = change { it.copy(businessCustomer = on) }
    fun buyer(name: String, tin: String, vat: String) = change { it.copy(buyerName = name, buyerTin = tin.filter(Char::isDigit).take(10), buyerVat = vat.filter(Char::isDigit).take(9)) }

    /** Issues the sale. The receipt is final at once; ZIMRA hears of it in the background. */
    fun charge() {
        val ui = mutable.value
        if (ui.issuing || ui.cart.isEmpty()) return
        mutable.update { it.copy(issuing = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = engine.sell(sale(ui))) {
                    is IssueResult.Issued -> mutable.update { SellUi(currency = it.currency, issued = result.receipt, warnings = result.warnings) }
                    is IssueResult.Refused -> mutable.update { it.copy(issuing = false, error = result.problems.joinToString("\n") { p -> "• " + p.message }) }
                }
            } catch (e: Exception) {
                mutable.update { it.copy(issuing = false, error = e.friendly()) }
            }
        }
    }

    fun dismissReceipt() = mutable.update { it.copy(issued = null, warnings = emptyList()) }

    fun dismissError() = mutable.update { it.copy(error = null) }

    private fun change(transform: (SellUi) -> SellUi) {
        mutable.update { current ->
            val next = transform(current)
            next.copy(preview = if (next.cart.isEmpty()) null else engine.preview(sale(next)), error = null)
        }
    }

    private fun sale(ui: SellUi) = Sale(
        lines = ui.cart,
        currency = ui.currency,
        payment = ui.payment,
        cashTendered = ui.cashTendered.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && ui.payment == PaymentChoice.CASH },
        buyer = if (ui.businessCustomer && ui.buyerName.isNotBlank()) {
            Buyer(ui.buyerName.trim(), ui.buyerTin, vatNumber = ui.buyerVat.ifBlank { null })
        } else {
            null
        },
    )
}
