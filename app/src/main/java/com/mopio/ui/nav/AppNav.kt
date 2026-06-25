package com.mopio.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mopio.container.ContainerManager
import com.mopio.git.GitController
import com.mopio.git.PatStorage
import com.mopio.phase0.Phase0Screen
import com.mopio.ui.build.BuildConsoleScreen
import com.mopio.ui.flash.FlashScreen
import com.mopio.ui.git.GitScreen
import com.mopio.ui.home.HomeScreen
import com.mopio.ui.monitor.MonitorScreen
import com.mopio.ui.project.ProjectScreen
import com.mopio.ui.settings.SettingsScreen
import com.mopio.ui.setup.SetupScreen
import com.mopio.usb.UsbPortBroker
import java.io.File

object Routes {
    const val SETUP    = "setup"
    const val HOME     = "home"
    const val SETTINGS = "settings"
    const val MONITOR  = "monitor"
    const val PHASE0   = "phase0"

    // Parameterised — encode path as URL-encoded query param to handle slashes
    fun project(projectPath: String)  = "project/${Uri.encode(projectPath)}"
    fun build(projectPath: String)    = "build/${Uri.encode(projectPath)}"
    fun flash(projectPath: String)    = "flash/${Uri.encode(projectPath)}"
    fun git(repoPath: String)         = "git/${Uri.encode(repoPath)}"
}

@Composable
fun AppNav(navController: NavHostController = rememberNavController()) {
    val ctx        = LocalContext.current
    val container  = remember { ContainerManager(ctx) }
    val usbBroker  = remember { UsbPortBroker(ctx) }
    val gitCtrl    = remember { GitController() }
    val patStorage = remember { PatStorage(ctx) }

    val start = if (container.isBootstrapped) Routes.HOME else Routes.SETUP

    NavHost(navController = navController, startDestination = start) {

        composable(Routes.SETUP) {
            SetupScreen(
                container  = container,
                onFinished = { navController.navigate(Routes.HOME) { popUpTo(Routes.SETUP) { inclusive = true } } }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                container     = container,
                patStorage    = patStorage,
                onOpenProject = { path -> navController.navigate(Routes.project(path)) },
                onSettings    = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(
            route = "project/{path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { back ->
            val path = Uri.decode(back.arguments?.getString("path") ?: "")
            ProjectScreen(
                projectDir  = File(path),
                container   = container,
                onBuild     = { navController.navigate(Routes.build(path)) },
                onFlash     = { navController.navigate(Routes.flash(path)) },
                onMonitor   = { navController.navigate(Routes.MONITOR) },
                onGit       = { navController.navigate(Routes.git(path)) },
                onBack      = { navController.popBackStack() }
            )
        }

        composable(
            route = "build/{path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { back ->
            val path = Uri.decode(back.arguments?.getString("path") ?: "")
            BuildConsoleScreen(
                projectDir = File(path),
                container  = container,
                onBack     = { navController.popBackStack() },
                onFlash    = { navController.navigate(Routes.flash(path)) }
            )
        }

        composable(
            route = "flash/{path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { back ->
            val path = Uri.decode(back.arguments?.getString("path") ?: "")
            FlashScreen(
                projectDir = File(path),
                container  = container,
                usbBroker  = usbBroker,
                onBack     = { navController.popBackStack() },
                onMonitor  = { navController.navigate(Routes.MONITOR) }
            )
        }

        composable(Routes.MONITOR) {
            MonitorScreen(
                usbBroker = usbBroker,
                onBack    = { navController.popBackStack() }
            )
        }

        composable(
            route = "git/{path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) { back ->
            val path = Uri.decode(back.arguments?.getString("path") ?: "")
            GitScreen(
                repoDir    = File(path),
                gitCtrl    = gitCtrl,
                patStorage = patStorage,
                onBack     = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                container  = container,
                patStorage = patStorage,
                onBack     = { navController.popBackStack() },
                onPhase0   = { navController.navigate(Routes.PHASE0) }
            )
        }

        composable(Routes.PHASE0) {
            Phase0Screen()
        }
    }
}
