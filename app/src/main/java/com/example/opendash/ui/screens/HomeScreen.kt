package com.example.opendash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.opendash.viewmodel.ConnectionState
import com.example.opendash.viewmodel.RouteViewModel

@Composable
fun HomeScreen(
    conn: ConnectionState,
    onNavigate: (String) -> Unit,
    routeViewModel: RouteViewModel,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    )
}
