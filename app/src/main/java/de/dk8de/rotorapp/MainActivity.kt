package de.dk8de.rotorapp

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.dk8de.rotorapp.ui.ControlScreen
import de.dk8de.rotorapp.ui.ProfilesScreen
import de.dk8de.rotorapp.ui.RotorViewModel
import de.dk8de.rotorapp.ui.theme.RotorAppTheme
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        val keepSplash = AtomicBoolean(true)
        splashScreen.setKeepOnScreenCondition { keepSplash.get() }

        super.onCreate(savedInstanceState)
        // Während die App sichtbar ist: Display an, kein Auto-Sperren
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            RotorAppTheme {
                val vm: RotorViewModel = viewModel()
                val state by vm.uiState.collectAsStateWithLifecycle()
                val startupReady by vm.startupReady.collectAsStateWithLifecycle()
                val nav = rememberNavController()
                val lifecycleOwner = LocalLifecycleOwner.current

                LaunchedEffect(startupReady) {
                    if (startupReady) keepSplash.set(false)
                }
                // Sicherheit: Splash spätestens nach kurzer Zeit weg
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(700)
                    keepSplash.set(false)
                }

                DisposableEffect(lifecycleOwner, vm) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> vm.onForeground()
                            Lifecycle.Event.ON_STOP -> vm.onBackground()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        vm.onBackground()
                    }
                }

                NavHost(navController = nav, startDestination = "control") {
                    composable("control") {
                        ControlScreen(
                            state = state,
                            onAzimuth = vm::setAzimuth,
                            onElevation = vm::setElevation,
                            onStopAxis = vm::stopAxis,
                            onHomeAxis = vm::startHoming,
                            onSetPwm = vm::setPwm,
                            onRefreshPwm = vm::refreshPwm,
                            onRefreshTemps = vm::refreshTemps,
                            onSelectAntenna = vm::selectAntenna,
                            onResetDwell = vm::resetDwellTimes,
                            onResetAccBins = vm::resetAccBins,
                            onOpenProfiles = { nav.navigate("profiles") },
                        )
                    }
                    composable("profiles") {
                        ProfilesScreen(
                            state = state,
                            onBack = { nav.popBackStack() },
                            onActivate = vm::activateProfile,
                            onSave = { vm.saveProfile(it, makeActive = true) },
                            onDelete = vm::deleteProfile,
                            onSaveAntenna = vm::saveAntenna,
                            onRefreshAntennas = vm::refreshAntennas,
                            onShowBeamOverlayChange = vm::setShowBeamOverlay,
                            onShowStromRingChange = vm::setShowStromRing,
                            onShowDwellRingChange = vm::setShowDwellRing,
                            onDwellFullMinutesChange = vm::setDwellFullMinutes,
                            onDwellSectorsChange = vm::setDwellSectors,
                            onHeatmapCustomChange = vm::setHeatmapCustom,
                            onHeatmapScaleSave = { custom, tb, nm, nx, tr ->
                                vm.setHeatmapScale(custom, tb, nm, nx, tr)
                            },
                            onHeatmapFromBins = vm::applyHeatmapFromCurrentBins,
                        )
                    }
                }
            }
        }
    }
}
