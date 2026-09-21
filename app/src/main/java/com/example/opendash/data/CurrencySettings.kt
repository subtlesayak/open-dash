package com.example.opendash.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class OpenDashCurrency(
    val code: String,
    val symbol: String,
    val displayName: String,
) {
    INR("INR", "\u20b9", "Indian rupee"),
    USD("USD", "$", "US dollar"),
    EUR("EUR", "\u20ac", "Euro"),
    GBP("GBP", "\u00a3", "British pound"),
    AUD("AUD", "A$", "Australian dollar"),
    CAD("CAD", "C$", "Canadian dollar"),
    SGD("SGD", "S$", "Singapore dollar"),
    AED("AED", "AED", "UAE dirham"),
}

object CurrencySettings {
    private const val PREFS = "appearance"
    private const val KEY_CURRENCY = "currency_code"

    private val _currency = MutableStateFlow(OpenDashCurrency.INR)
    val currency = _currency.asStateFlow()

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CURRENCY, OpenDashCurrency.INR.code)
        _currency.value = OpenDashCurrency.entries.firstOrNull { it.code == saved } ?: OpenDashCurrency.INR
    }

    fun select(context: Context, currency: OpenDashCurrency) {
        _currency.value = currency
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CURRENCY, currency.code)
            .apply()
    }
}

fun formatCurrencyAmount(amount: Double, currency: OpenDashCurrency, decimals: Int = 0): String {
    val formatted = "%,.${decimals}f".format(Locale.US, amount)
    return if (currency.symbol.length > 1) "${currency.symbol} $formatted" else "${currency.symbol}$formatted"
}
