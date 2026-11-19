package com.larp.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.larp.launcher.core.design.theme.LarpTheme
import com.larp.launcher.data.local.prefs.SettingsPrefs
import com.larp.launcher.data.repository.AuthRepository
import com.larp.launcher.feature.navigation.LarpNavHost
import com.larp.launcher.feature.navigation.Routes
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var settingsPrefs: SettingsPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dynamicColor by settingsPrefs.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
            val darkMode by settingsPrefs.darkMode.collectAsStateWithLifecycle(initialValue = "system")
            val isDark = when (darkMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            LarpTheme(darkTheme = isDark, dynamicColor = dynamicColor) {
                val navController = rememberNavController()
                val startDestination = remember(authRepository.isLoggedIn()) {
                    if (authRepository.isLoggedIn()) Routes.LAUNCHER else Routes.LOGIN
                }
                LarpNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    onLogout = {
                        authRepository.logout()
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(Routes.LAUNCHER) { inclusive = true }
                        }
                    }
                )
            }
        }
        handleOAuthRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthRedirect(intent)
    }

    private fun handleOAuthRedirect(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme == "com.larp.launcher" && data.host == "oauth") {
            val grant = data.getQueryParameter("grant") ?: data.getQueryParameter("code") ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                kotlinx.coroutines.runBlocking {
                    runCatching { authRepository.finishLogin(grant) }
                }
            }
        }
    }
}
