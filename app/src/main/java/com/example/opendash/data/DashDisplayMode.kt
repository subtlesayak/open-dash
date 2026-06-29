package com.example.opendash.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object DashDisplayMode {
    private const val NIGHT_THRESHOLD = 0x58
    private const val DAY_THRESHOLD = 0x60

    private val _ambientLight = MutableStateFlow<Int?>(null)
    val ambientLight = _ambientLight.asStateFlow()

    private val _nightMode = MutableStateFlow(true)
    val nightMode = _nightMode.asStateFlow()

    fun updateFromDashAmbient(raw: Int) {
        val value = raw.coerceIn(0, 255)
        _ambientLight.value = value
        _nightMode.value = when {
            value <= NIGHT_THRESHOLD -> true
            value >= DAY_THRESHOLD -> false
            else -> _nightMode.value
        }
    }
}
