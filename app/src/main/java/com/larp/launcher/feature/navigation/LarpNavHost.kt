package com.larp.launcher.feature.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.larp.launcher.feature.announcements.AnnouncementsScreen
import com.larp.launcher.feature.auth.LoginScreen
import com.larp.launcher.feature.broadcast.BroadcastScreen
import com.larp.launcher.feature.launcher.LauncherScreen
import com.larp.launcher.feature.malum.MalumScreen
import com.larp.launcher.feature.mods.browser.ModsScreen
import com.larp.launcher.feature.mods.mine.MyModsScreen
import com.larp.launcher.feature.rooms.RoomsScreen
import com.larp.launcher.feature.settings.SettingsScreen
import com.larp.launcher.feature.voice.VoiceScreen

object Routes {
    const val LOGIN = "login"
    const val LAUNCHER = "launcher"
    const val MODS = "mods"
    const val MY_MODS = "my_mods"
    const val ROOMS = "rooms"
    const val ANNOUNCEMENTS = "announcements"
    const val BROADCAST = "broadcast"
    const val VOICE = "voice/{roomId}"
    const val MALUM = "malum"
    const val SETTINGS = "settings"

    fun voice(roomId: String): String = "voice/$roomId"
}

@Composable
fun LarpNavHost(
    navController: NavHostController,
    startDestination: String,
    onLogout: () -> Unit
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.LOGIN) {
            LoginScreen(
                onLogin = {},
                onOpenLicenses = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.LAUNCHER) {
            LauncherScreen(
                onLaunchGame = { navController.navigate(Routes.MY_MODS) },
                onNavigateTo = { dest ->
                    when (dest) {
                        com.larp.launcher.feature.launcher.LarpDestination.Mods -> navController.navigate(Routes.MODS)
                        com.larp.launcher.feature.launcher.LarpDestination.MyMods -> navController.navigate(Routes.MY_MODS)
                        com.larp.launcher.feature.launcher.LarpDestination.Rooms -> navController.navigate(Routes.ROOMS)
                        com.larp.launcher.feature.launcher.LarpDestination.Mural -> navController.navigate(Routes.ANNOUNCEMENTS)
                        com.larp.launcher.feature.launcher.LarpDestination.Broadcast -> navController.navigate(Routes.BROADCAST)
                        com.larp.launcher.feature.launcher.LarpDestination.Voice -> navController.navigate(Routes.voice("lobby"))
                        com.larp.launcher.feature.launcher.LarpDestination.Malum -> navController.navigate(Routes.MALUM)
                        com.larp.launcher.feature.launcher.LarpDestination.Settings -> navController.navigate(Routes.SETTINGS)
                    }
                },
                onLogout = onLogout
            )
        }

        composable(Routes.MODS) {
            ModsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.MY_MODS) {
            MyModsScreen(
                onBack = { navController.popBackStack() },
                onLaunch = { navController.navigate(Routes.LAUNCHER) { popUpTo(Routes.LAUNCHER) { inclusive = true } } }
            )
        }

        composable(Routes.ROOMS) {
            RoomsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ANNOUNCEMENTS) {
            AnnouncementsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.BROADCAST) {
            BroadcastScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.VOICE,
            arguments = listOf(navArgument("roomId") { type = NavType.StringType; defaultValue = "lobby" })
        ) { entry ->
            val roomId = entry.arguments?.getString("roomId") ?: "lobby"
            VoiceScreen(roomId = roomId, onBack = { navController.popBackStack() })
        }

        composable(Routes.MALUM) {
            MalumScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenLicenses = {}
            )
        }
    }
}
