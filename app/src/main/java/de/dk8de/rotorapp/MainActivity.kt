package de.dk8de.rotorapp

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import de.dk8de.rotorapp.ui.UpdateDownload
import de.dk8de.rotorapp.ui.theme.BridgeAccent
import de.dk8de.rotorapp.ui.theme.BridgeMuted
import de.dk8de.rotorapp.ui.theme.BridgePanel
import de.dk8de.rotorapp.ui.theme.BridgeText
import de.dk8de.rotorapp.ui.theme.RotorAppTheme
import java.util.concurrent.atomic.AtomicBoolean

/** AppCompatActivity nötig, damit setApplicationLocales die UI neu lädt. */
class MainActivity : AppCompatActivity() {
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
                val update by vm.updateInfo.collectAsStateWithLifecycle()
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
                            onParkAzimuth = vm::parkAzimuth,
                            onSaveFavorite = vm::saveFavorite,
                            onDeleteFavorite = vm::deleteFavorite,
                            onGoFavorite = vm::goFavorite,
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
                            onLocationSave = { lat, lon, loc ->
                                vm.setLocation(lat, lon, loc)
                            },
                            onAppLanguageChange = vm::setAppLanguage,
                            onCheckUpdate = vm::checkForUpdateNow,
                            updateCheckState = vm.updateCheckState
                                .collectAsStateWithLifecycle().value,
                            onExportSettings = vm::exportSettings,
                            onImportSettings = vm::importSettings,
                            onBackupStateSeen = vm::clearBackupState,
                            backupState = vm.backupState
                                .collectAsStateWithLifecycle().value,
                        )
                    }
                }

                update?.let { info ->
                    UpdateDialog(
                        version = info.version,
                        download = vm.updateDownload.collectAsStateWithLifecycle().value,
                        onDownload = vm::downloadUpdate,
                        onGrantPermission = vm::openInstallPermission,
                        onDismiss = vm::dismissUpdate,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateDialog(
    version: String,
    download: UpdateDownload,
    onDownload: () -> Unit,
    onGrantPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val running = download as? UpdateDownload.Running
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_title), color = BridgeText) },
        text = {
            Column {
                Text(
                    stringResource(R.string.update_message, version, AppVersion.NAME),
                    color = BridgeMuted,
                )
                when (download) {
                    is UpdateDownload.Running -> {
                        Spacer(Modifier.height(12.dp))
                        if (running!!.progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { running.progress },
                                color = BridgeAccent,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(
                                    R.string.update_downloading,
                                    (running.progress * 100).toInt(),
                                ),
                                color = BridgeMuted,
                            )
                        } else {
                            LinearProgressIndicator(
                                color = BridgeAccent,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    UpdateDownload.NeedsPermission -> {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.update_permission_hint), color = BridgeText)
                    }

                    UpdateDownload.Failed -> {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.update_download_failed), color = BridgeText)
                    }

                    UpdateDownload.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (download) {
                // Während des Ladens keine zweite Anforderung zulassen.
                is UpdateDownload.Running -> Unit
                UpdateDownload.NeedsPermission -> TextButton(onClick = onGrantPermission) {
                    Text(stringResource(R.string.update_grant_permission), color = BridgeAccent)
                }

                UpdateDownload.Failed -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.update_retry), color = BridgeAccent)
                }

                UpdateDownload.Idle -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.update_download), color = BridgeAccent)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(
                        if (running != null) R.string.action_cancel else R.string.update_later,
                    ),
                    color = BridgeMuted,
                )
            }
        },
        containerColor = BridgePanel,
    )
}
