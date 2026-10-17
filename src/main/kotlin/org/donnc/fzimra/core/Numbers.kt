package org.donnc.fzimra.core

import java.math.BigDecimal
import java.math.BigInteger

/**
 * Converts a number from the host app's own model to an exact [BigDecimal].
 *
 * Integers and [BigDecimal] are taken as they are. [Double] and [Float] are
 * taken through their shortest decimal representation, so `19.99` stays
 * `19.99` and never becomes `19.98999…`. NaN and infinity are refused.
 */
internal fun exactDecimal(value: Number, field: String): BigDecimal = when (value) {
    is BigDecimal -> value
    is Int, is Long, is Short, is Byte -> BigDecimal.valueOf(value.toLong())
    is BigInteger -> BigDecimal(value)
    is Double -> {
        if (!value.isFinite()) throw FiscalValueException("$field: $value is not a finite number")
        BigDecimal(value.toString())
    }
    is Float -> {
        if (!value.isFinite()) throw FiscalValueException("$field: $value is not a finite number")
        BigDecimal(value.toString())
    }
    else -> throw FiscalValueException("$field: unsupported number type ${value.javaClass.name}")
}

/** Number of decimal places of [value], ignoring trailing zeros. */
internal fun decimalPlaces(value: BigDecimal): Int = maxOf(value.stripTrailingZeros().scale(), 0)
