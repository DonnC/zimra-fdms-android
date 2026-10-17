package org.donnc.fzimra.example.ui

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.donnc.fzimra.example.R
import org.donnc.fzimra.example.fiscal.FiscalEngine
import org.donnc.fzimra.example.ui.day.DayScreen
import org.donnc.fzimra.example.ui.receipts.ReceiptsScreen
import org.donnc.fzimra.example.ui.sell.SellScreen
import org.donnc.fzimra.example.ui.setup.SetupScreen
import org.donnc.fzimra.example.ui.traffic.TrafficScreen

private enum class Destination(val route: String, val label: String, @DrawableRes val icon: Int) {
    SELL("sell", "Sell", R.drawable.ic_sell),
    RECEIPTS("receipts", "Receipts", R.drawable.ic_receipts),
    DAY("day", "Fiscal day", R.drawable.ic_status),
    TRAFFIC("traffic", "Traffic", R.drawable.ic_traffic),
    SETUP("setup", "Setup", R.drawable.ic_setup),
}

@Composable
fun AppRoot(engine: FiscalEngine) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    fun go(destination: Destination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { d ->
                    NavigationBarItem(
                        selected = route == d.route,
                        onClick = { go(d) },
                        icon = { Icon(painterResource(d.icon), contentDescription = null) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController, startDestination = Destination.SELL.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Destination.SELL.route) { SellScreen(engine, onSetup = { go(Destination.SETUP) }) }
            composable(Destination.RECEIPTS.route) { ReceiptsScreen(engine, onSetup = { go(Destination.SETUP) }) }
            composable(Destination.DAY.route) { DayScreen(engine, onSetup = { go(Destination.SETUP) }) }
            composable(Destination.TRAFFIC.route) { TrafficScreen(engine) }
            composable(Destination.SETUP.route) { SetupScreen(engine, onReady = { go(Destination.SELL) }) }
        }
    }
}
