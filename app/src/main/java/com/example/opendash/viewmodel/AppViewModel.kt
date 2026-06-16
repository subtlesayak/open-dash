package com.example.opendash.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionState { Connected, Searching, Offline }

class AppViewModel : ViewModel() {
    private val _conn = MutableStateFlow(ConnectionState.Connected)
    val conn = _conn.asStateFlow()

    private val _garageTab = MutableStateFlow("Maintenance")
    val garageTab = _garageTab.asStateFlow()

    fun setConn(state: ConnectionState) { _conn.value = state }
    fun setGarageTab(tab: String) { _garageTab.value = tab }
}
