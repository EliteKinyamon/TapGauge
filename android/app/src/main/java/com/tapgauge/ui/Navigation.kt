package com.tapgauge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tapgauge.TapGaugeApplication
import com.tapgauge.ui.screens.AddTankScreen
import com.tapgauge.ui.screens.CalibrateScreen
import com.tapgauge.ui.screens.DiagnosticsScreen
import com.tapgauge.ui.screens.HistoryScreen
import com.tapgauge.ui.screens.HomeScreen
import com.tapgauge.ui.screens.MeasureScreen
import com.tapgauge.ui.screens.OnboardingScreen
import com.tapgauge.ui.screens.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ADD_TANK = "add_tank"
    const val MEASURE = "measure/{tankId}"
    const val CALIBRATE = "calibrate/{tankId}"
    const val HISTORY = "history/{tankId}"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"

    fun measure(id: Long) = "measure/$id"
    fun calibrate(id: Long) = "calibrate/$id"
    fun history(id: Long) = "history/$id"
}

@Composable
fun TapGaugeApp() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as TapGaugeApplication
    val start = if (app.settings.onboardingComplete) Routes.HOME else Routes.ONBOARDING

    val tankIdArg = listOf(navArgument("tankId") { type = NavType.LongType })

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = {
                app.settings.onboardingComplete = true
                nav.navigate(Routes.HOME) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onAddTank = { nav.navigate(Routes.ADD_TANK) },
                onOpenTank = { nav.navigate(Routes.measure(it)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.ADD_TANK) {
            AddTankScreen(
                onCreated = { id ->
                    nav.popBackStack()
                    nav.navigate(Routes.measure(id))
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.MEASURE, arguments = tankIdArg) { entry ->
            val id = entry.arguments!!.getLong("tankId")
            MeasureScreen(
                tankId = id,
                onCalibrate = { nav.navigate(Routes.calibrate(id)) },
                onHistory = { nav.navigate(Routes.history(id)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.CALIBRATE, arguments = tankIdArg) { entry ->
            val id = entry.arguments!!.getLong("tankId")
            CalibrateScreen(tankId = id, onBack = { nav.popBackStack() })
        }
        composable(Routes.HISTORY, arguments = tankIdArg) { entry ->
            val id = entry.arguments!!.getLong("tankId")
            HistoryScreen(tankId = id, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
            )
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(onBack = { nav.popBackStack() })
        }
    }
}
