package com.larp.launcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.larp.launcher.core.console.Console
import com.larp.launcher.core.design.theme.LarpTheme
import com.larp.launcher.data.local.prefs.SettingsPrefs
import com.larp.launcher.data.repository.AuthRepository
import com.larp.launcher.feature.navigation.LarpNavHost
import com.larp.launcher.feature.navigation.Routes
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var settingsPrefs: SettingsPrefs
    @Inject lateinit var console: Console

    companion object {
        @Volatile var authStateChanged: Long = 0
        const val EXTRA_DEVKIT = "larp_extra_devkit"
        @Volatile var pendingDevKitSnapshot: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        console.i("MainActivity", "onCreate")
        setContent {
            val dynamicColor by settingsPrefs.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
            val darkMode by settingsPrefs.darkMode.collectAsStateWithLifecycle(initialValue = "system")
            val isDark = when (darkMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            val authTick by AuthStateHolder.state.collectAsState()
            val overlayTick by OverlayPermissionHolder.state.collectAsState()
            val startDestination = remember(authTick) {
                val loggedIn = authRepository.isLoggedIn()
                console.d("MainActivity", "startDestination: ${if (loggedIn) Routes.LAUNCHER else Routes.LOGIN}")
                if (loggedIn) Routes.LAUNCHER else Routes.LOGIN
            }
            val overlayGranted = remember(overlayTick) {
                OverlayPermissionHolder.isGranted(this)
            }
            LarpTheme(darkTheme = isDark, dynamicColor = dynamicColor) {
                val navController = rememberNavController()
                LaunchedEffect(authTick) {
                    val pending = pendingDevKitSnapshot
                    if (pending != null && authRepository.isLoggedIn()) {
                        pendingDevKitSnapshot = null
                        navController.navigate(Routes.DEVKIT) {
                            launchSingleTop = true
                        }
                    }
                }
                LarpNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    onLogout = {
                        authRepository.logout()
                        AuthStateHolder.bump()
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(Routes.LAUNCHER) { inclusive = true }
                        }
                    }
                )
            }
        }
        handleOAuthRedirect(intent)
    }

    override fun onResume() {
        super.onResume()
        if (lastOverlayGranted != OverlayPermissionHolder.isGranted(this)) {
            lastOverlayGranted = OverlayPermissionHolder.isGranted(this)
            OverlayPermissionHolder.bump()
            console.i("MainActivity", "Overlay permission changed: $lastOverlayGranted")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val now = OverlayPermissionHolder.isGranted(this)
            if (lastOverlayGranted != now) {
                lastOverlayGranted = now
                OverlayPermissionHolder.bump()
                console.i("MainActivity", "Overlay permission (focus) changed: $now")
            }
        }
    }

    private var lastOverlayGranted: Boolean = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthRedirect(intent)
    }

    private fun handleOAuthRedirect(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme == "com.larp.launcher" && data.host == "oauth") {
            val grant = data.getQueryParameter("grant") ?: data.getQueryParameter("code") ?: return
            console.i("MainActivity", "OAuth redirect recebido, trocando grant por token")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    runBlocking { authRepository.finishLogin(grant) }
                    console.i("MainActivity", "Login finalizado, atualizando state")
                    AuthStateHolder.bump()
                }.onFailure { t -> console.e("MainActivity", "Falha no finishLogin", t) }
            }
        } else if (data.scheme == "com.larp.launcher" && data.host == "devkit") {
            val snapshotId = data.getQueryParameter("snapshot") ?: ""
            console.i("MainActivity", "Dev Kit deep link recebido: snapshot=$snapshotId")
            pendingDevKitSnapshot = snapshotId
            AuthStateHolder.bump()
        }
    }
}

object AuthStateHolder {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val state = _state
    fun bump() {
        _state.value = _state.value + 1
    }
}
