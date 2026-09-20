@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.dk8de.rotorapp.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import de.dk8de.rotorapp.data.PositionFavorite
import de.dk8de.rotorapp.rotor.RotorAxis
import de.dk8de.rotorapp.ui.components.AzimuthCompass
import de.dk8de.rotorapp.ui.components.BridgeButton
import de.dk8de.rotorapp.ui.components.BridgeButtonTone
import de.dk8de.rotorapp.ui.components.ElevationCompass
import de.dk8de.rotorapp.ui.components.antennaBeamColor
import de.dk8de.rotorapp.ui.theme.BridgeAccent
import de.dk8de.rotorapp.ui.theme.BridgeBg
import de.dk8de.rotorapp.ui.theme.BridgeButtonBorder
import de.dk8de.rotorapp.ui.theme.BridgeIst
import de.dk8de.rotorapp.ui.theme.BridgeMuted
import de.dk8de.rotorapp.ui.theme.BridgePanel
import de.dk8de.rotorapp.ui.theme.BridgeSoll
import de.dk8de.rotorapp.ui.theme.BridgeStop
import de.dk8de.rotorapp.ui.theme.BridgeText
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val PAGE_QUICK = 0
private const val PAGE_AZ = 1
private const val PAGE_EL = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(
    state: AppUiState,
    onAzimuth: (Double) -> Unit,
    onElevation: (Double) -> Unit,
    onStopAxis: (RotorAxis) -> Unit,
    onHomeAxis: (RotorAxis) -> Unit,
    onSetPwm: (RotorAxis, Int) -> Unit,
    onRefreshPwm: () -> Unit,
    onRefreshTemps: () -> Unit,
    onSelectAntenna: (Int) -> Unit,
    onResetDwell: () -> Unit,
    onParkAzimuth: () -> Unit,
    onSaveFavorite: (String) -> Unit,
    onDeleteFavorite: (String) -> Unit,
    onGoFavorite: (String) -> Unit,
    onOpenProfiles: () -> Unit,
) {
    val connected = state.rotor.connected
    val canMoveAz = connected && state.rotor.azOnline && state.rotor.azReferenced && !state.rotor.azHoming
    val canMoveEl = connected && state.rotor.elOnline && state.rotor.elReferenced && !state.rotor.elHoming
    val profile = state.activeProfile
    val elevationEnabled = profile?.enableEl == true
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Querformat + EL: Quick | AZ+EL nebeneinander | Karte
    val dualAxes = landscape && elevationEnabled
    val pageLabels = when {
        dualAxes -> listOf("Quick", "AZ/EL", "Karte")
        elevationEnabled -> listOf("Quick", "AZ", "EL", "Karte")
        else -> listOf("Quick", "AZ", "Karte")
    }
    val pageCount = pageLabels.size
    val mapPageIndex = pageLabels.lastIndex
    val pagerState = rememberPagerState(initialPage = PAGE_AZ.coerceAtMost(pageCount - 1)) {
        pageCount
    }
    val scope = rememberCoroutineScope()
    val onMapPage = pagerState.currentPage == mapPageIndex
    // Karte nach erstem Besuch behalten (WebView nicht neu aufbauen)
    var mapCached by remember { mutableStateOf(false) }
    LaunchedEffect(onMapPage) {
        if (onMapPage) mapCached = true
    }

    // Seitenindex nur bei Quer-/Hochkant-Wechsel mappen (sonst springt EL→Karte)
    var prevDualAxes by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(dualAxes, pageCount, elevationEnabled) {
        val page = pagerState.currentPage
        val wasDual = prevDualAxes
        prevDualAxes = dualAxes
        if (wasDual == null || wasDual == dualAxes) {
            if (page >= pageCount) {
                pagerState.scrollToPage((pageCount - 1).coerceAtLeast(0))
            }
            return@LaunchedEffect
        }
        val target = if (dualAxes && elevationEnabled) {
            // Hochkant → Quer: AZ/EL → Kompass, Karte → Karte
            when (page) {
                0 -> 0
                1, 2 -> 1
                else -> 2
            }
        } else if (!dualAxes && elevationEnabled) {
            // Quer → Hochkant: AZ/EL → AZ, Karte → Karte
            when (page) {
                0 -> 0
                1 -> 1
                else -> 3
            }
        } else {
            page.coerceAtMost(pageCount - 1)
        }
        if (target != page && target in 0 until pageCount) {
            pagerState.scrollToPage(target)
        }
    }

    Scaffold(
        containerColor = BridgeBg,
        topBar = {
            if (!landscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BridgePanel)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .height(36.dp)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Rotor App",
                        color = BridgeText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (connected) BridgeIst else BridgeStop)
                            .border(1.dp, BridgeButtonBorder, CircleShape),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (landscape && !onMapPage) {
                            Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                        } else if (onMapPage) {
                            Modifier.padding(0.dp)
                        } else {
                            Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        },
                    ),
            ) {
                if (state.rotor.statusText.isNotBlank() && !onMapPage) {
                    Text(
                        text = state.rotor.statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            state.rotor.hasFault -> BridgeStop
                            state.rotor.hasWarning -> BridgeAccent
                            state.rotor.homing -> BridgeAccent
                            connected -> BridgeStop
                            else -> BridgeMuted
                        },
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        // Auf der Karte kein Wischen — sonst kollidiert es mit Pan/Zoom
                        userScrollEnabled = !onMapPage,
                        // Nachbarseiten behalten (Kompass nicht neu aufbauen)
                        beyondViewportPageCount = (pageCount - 1).coerceAtLeast(1),
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        when {
                            page == mapPageIndex -> Box(Modifier.fillMaxSize())
                            page == PAGE_QUICK -> QuickSettingsPage(
                                state = state,
                                connected = connected,
                                elevationEnabled = elevationEnabled,
                                onSetPwm = onSetPwm,
                                onRefreshPwm = onRefreshPwm,
                                onRefreshTemps = onRefreshTemps,
                                onSelectAntenna = onSelectAntenna,
                                onResetDwell = onResetDwell,
                                onParkAzimuth = onParkAzimuth,
                                onHomeAz = { onHomeAxis(RotorAxis.AZ) },
                                onHomeEl = { onHomeAxis(RotorAxis.EL) },
                                onGoFavorite = onGoFavorite,
                                onDeleteFavorite = onDeleteFavorite,
                            )
                            dualAxes && page == PAGE_AZ -> DualAxesLandscapePage(
                                state = state,
                                canMoveAz = canMoveAz,
                                canMoveEl = canMoveEl,
                                connected = connected,
                                onAzimuth = onAzimuth,
                                onElevation = onElevation,
                                onStopAz = { onStopAxis(RotorAxis.AZ) },
                                onStopEl = { onStopAxis(RotorAxis.EL) },
                                onSaveFavorite = onSaveFavorite,
                            )
                            page == PAGE_AZ -> AzimuthPage(
                                state = state,
                                canMove = canMoveAz,
                                connected = connected,
                                onAzimuth = onAzimuth,
                                onStop = { onStopAxis(RotorAxis.AZ) },
                                onSaveFavorite = onSaveFavorite,
                            )
                            page == PAGE_EL && elevationEnabled -> ElevationPage(
                                state = state,
                                canMove = canMoveEl,
                                connected = connected,
                                onElevation = onElevation,
                                onStop = { onStopAxis(RotorAxis.EL) },
                                onSaveFavorite = onSaveFavorite,
                            )
                        }
                    }

                    if (mapCached) {
                        MapScreen(
                            state = state,
                            onMapClickBearing = onAzimuth,
                            active = onMapPage,
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (onMapPage) 1f else 0f)
                                .zIndex(if (onMapPage) 1f else -1f),
                        )
                    }
                }

                // Fußzeile: Seitenpunkte mittig, Einstellungen rechts
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.size(40.dp))
                    PageIndicator(
                        labels = pageLabels,
                        currentPage = pagerState.currentPage,
                        onSelectPage = { index ->
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onOpenProfiles,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Einstellungen",
                            tint = BridgeText,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageIndicator(
    labels: List<String>,
    currentPage: Int,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier.clickable(
                onClick = {
                    val next = (currentPage + 1) % labels.size
                    onSelectPage(next)
                },
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = labels.getOrElse(currentPage) { "" },
                style = MaterialTheme.typography.labelMedium,
                color = BridgeMuted,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(labels.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { onSelectPage(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (index == currentPage) 14.dp else 11.dp)
                                .clip(CircleShape)
                                .background(if (index == currentPage) BridgeAccent else BridgeButtonBorder),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSettingsPage(
    state: AppUiState,
    connected: Boolean,
    elevationEnabled: Boolean,
    onSetPwm: (RotorAxis, Int) -> Unit,
    onRefreshPwm: () -> Unit,
    onRefreshTemps: () -> Unit,
    onSelectAntenna: (Int) -> Unit,
    onResetDwell: () -> Unit,
    onParkAzimuth: () -> Unit,
    onHomeAz: () -> Unit,
    onHomeEl: () -> Unit,
    onGoFavorite: (String) -> Unit,
    onDeleteFavorite: (String) -> Unit,
) {
    LaunchedEffect(connected) {
        if (connected) {
            onRefreshPwm()
            onRefreshTemps()
        }
    }

    var azSlider by remember { mutableFloatStateOf((state.rotor.pwmAz ?: 70).coerceIn(30, 100).toFloat()) }
    var elSlider by remember { mutableFloatStateOf((state.rotor.pwmEl ?: 70).coerceIn(30, 100).toFloat()) }
    var lastSentAz by remember { mutableStateOf(-1) }
    var lastSentEl by remember { mutableStateOf(-1) }
    var favExpanded by remember { mutableStateOf(false) }
    var selectedFavId by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val favorites = state.favorites
    val selectedFav = favorites.find { it.id == selectedFavId }
    LaunchedEffect(favorites) {
        if (selectedFavId != null && favorites.none { it.id == selectedFavId }) {
            selectedFavId = null
        }
    }

    LaunchedEffect(state.rotor.pwmAz) {
        state.rotor.pwmAz?.let {
            val v = it.coerceIn(30, 100)
            azSlider = v.toFloat()
            lastSentAz = v
        }
    }
    LaunchedEffect(state.rotor.pwmEl) {
        state.rotor.pwmEl?.let {
            val v = it.coerceIn(30, 100)
            elSlider = v.toFloat()
            lastSentEl = v
        }
    }

    if (confirmDelete && selectedFav != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Favorit löschen?", color = BridgeText) },
            text = {
                Text(
                    "„${selectedFav.name}“ wirklich löschen?",
                    color = BridgeMuted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteFavorite(selectedFav.id)
                        selectedFavId = null
                        confirmDelete = false
                    },
                ) {
                    Text("Ja", color = BridgeStop)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Nein", color = BridgeMuted)
                }
            },
            containerColor = BridgePanel,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AntennaSelectCard(
            antennas = state.rotor.antennas,
            selected = state.rotor.selectedAntenna,
            connected = connected,
            onSelect = onSelectAntenna,
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = BridgePanel),
            border = BorderStroke(1.dp, BridgeButtonBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Favoriten", color = BridgeText, fontWeight = FontWeight.SemiBold)
                ExposedDropdownMenuBox(
                    expanded = favExpanded,
                    onExpandedChange = { favExpanded = it && favorites.isNotEmpty() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextField(
                        value = selectedFav?.let { formatFavoriteLabel(it, elevationEnabled) }
                            ?: if (favorites.isEmpty()) "Keine Favoriten" else "Favorit wählen",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        enabled = favorites.isNotEmpty(),
                        textStyle = TextStyle(
                            color = BridgeText,
                            fontSize = 14.sp,
                        ),
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = favExpanded)
                        },
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = BridgeText,
                            unfocusedTextColor = BridgeText,
                            disabledTextColor = BridgeMuted,
                            focusedContainerColor = BridgeBg,
                            unfocusedContainerColor = BridgeBg,
                            disabledContainerColor = BridgeBg,
                            focusedIndicatorColor = BridgeButtonBorder,
                            unfocusedIndicatorColor = BridgeButtonBorder,
                            disabledIndicatorColor = BridgeButtonBorder,
                            focusedTrailingIconColor = BridgeMuted,
                            unfocusedTrailingIconColor = BridgeMuted,
                        ),
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                            .height(56.dp),
                    )
                    ExposedDropdownMenu(
                        expanded = favExpanded,
                        onDismissRequest = { favExpanded = false },
                        containerColor = BridgePanel,
                    ) {
                        favorites.forEach { fav ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        formatFavoriteLabel(fav, elevationEnabled),
                                        color = BridgeText,
                                    )
                                },
                                onClick = {
                                    selectedFavId = fav.id
                                    favExpanded = false
                                },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BridgeButton(
                        text = "GO",
                        onClick = { selectedFavId?.let(onGoFavorite) },
                        enabled = connected && selectedFavId != null &&
                            state.rotor.azReferenced && !state.rotor.azHoming,
                        compact = true,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    BridgeButton(
                        text = "DEL",
                        onClick = { confirmDelete = true },
                        enabled = selectedFavId != null,
                        tone = BridgeButtonTone.Stop,
                        compact = true,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }

        PwmSliderCard(
            title = "Geschwindigkeit AZ (PWM)",
            value = azSlider,
            enabled = connected,
            onValueChange = { raw ->
                val v = raw.coerceIn(30f, 100f)
                azSlider = v
                val p = v.roundToInt()
                if (p != lastSentAz) {
                    lastSentAz = p
                    onSetPwm(RotorAxis.AZ, p)
                }
            },
        )
        if (elevationEnabled) {
            PwmSliderCard(
                title = "Geschwindigkeit EL (PWM)",
                value = elSlider,
                enabled = connected,
                onValueChange = { raw ->
                    val v = raw.coerceIn(30f, 100f)
                    elSlider = v
                    val p = v.roundToInt()
                    if (p != lastSentEl) {
                        lastSentEl = p
                        onSetPwm(RotorAxis.EL, p)
                    }
                },
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = BridgePanel),
            border = BorderStroke(1.dp, BridgeButtonBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Statistik zurücksetzen", color = BridgeText, fontWeight = FontWeight.SemiBold)
                BridgeButton(
                    text = "STANDZEIT",
                    onClick = onResetDwell,
                    enabled = connected,
                    compact = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                )
            }
        }

        BridgeButton(
            text = "PARKEN",
            onClick = onParkAzimuth,
            enabled = connected &&
                state.rotor.azReferenced && !state.rotor.azHoming &&
                (!elevationEnabled || (state.rotor.elReferenced && !state.rotor.elHoming)),
            compact = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BridgeButton(
                text = "HOME AZ",
                onClick = onHomeAz,
                enabled = connected && !state.rotor.azHoming,
                compact = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            if (elevationEnabled) {
                BridgeButton(
                    text = "HOME EL",
                    onClick = onHomeEl,
                    enabled = connected && !state.rotor.elHoming,
                    compact = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun azimuthHudTopLeft(state: AppUiState): Pair<String, String> {
    val connected = state.rotor.connected
    val v = if (!connected) "—" else state.rotor.tempAmbientC?.let { "%.1f°C".format(it) } ?: "—"
    return "Außen" to v
}

@Composable
private fun azimuthHudTopRight(state: AppUiState): List<Pair<String, String>> {
    val connected = state.rotor.connected
    fun t(v: Double?): String =
        if (!connected) "—" else v?.let { "%.1f°C".format(it) } ?: "—"
    val label = if (state.activeProfile?.enableEl == true) "Motor AZ" else "Motor"
    return listOf(label to t(state.rotor.tempMotorAzC))
}

@Composable
private fun elevationHudTopRight(state: AppUiState): Pair<String, String> {
    val connected = state.rotor.connected
    val v = if (!connected) "—" else state.rotor.tempMotorElC?.let { "%.1f°C".format(it) } ?: "—"
    return "Motor EL" to v
}

@Composable
private fun azimuthHudBottomLeft(state: AppUiState): Pair<String, String>? {
    if (state.activeProfile?.enableWind != true) return null
    val connected = state.rotor.connected
    val windVal = when {
        !connected -> "—"
        state.rotor.windHwEnabled != true && state.rotor.windKmh == null -> "—"
        else -> state.rotor.windKmh?.let { "%.1f km/h".format(it) } ?: "— km/h"
    }
    return "Wind" to windVal
}

@Composable
private fun AntennaSelectCard(
    antennas: List<de.dk8de.rotorapp.rotor.AntennaSlot>,
    selected: Int,
    connected: Boolean,
    onSelect: (Int) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BridgePanel),
        border = BorderStroke(1.dp, BridgeButtonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Antenne",
                color = BridgeText,
                fontWeight = FontWeight.SemiBold,
            )
            if (!connected) {
                Text(
                    "Verbinden, um Antenne zu wählen",
                    color = BridgeMuted,
                    fontSize = 13.sp,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..3).forEach { slot ->
                    val ant = antennas.getOrNull(slot - 1)
                    val label = ant?.label(slot) ?: "Antenne $slot (0°)"
                    val isSel = selected == slot
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSel) BridgeAccent.copy(alpha = 0.18f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSel) BridgeAccent else BridgeButtonBorder,
                                RoundedCornerShape(6.dp),
                            )
                            .then(
                                if (connected) {
                                    Modifier.clickable { onSelect(slot) }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            color = if (connected) BridgeText else BridgeMuted,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSel) {
                            Text("aktiv", color = BridgeAccent, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PwmSliderCard(
    title: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BridgePanel),
        border = BorderStroke(1.dp, BridgeButtonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, color = BridgeText, fontWeight = FontWeight.SemiBold)
                Text("${value.roundToInt()} %", color = BridgeAccent, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = value.coerceIn(30f, 100f),
                onValueChange = onValueChange,
                enabled = enabled,
                valueRange = 30f..100f,
                steps = 13, // 5%-Schritte von 30…100
                colors = SliderDefaults.colors(
                    thumbColor = BridgeAccent,
                    activeTrackColor = BridgeAccent,
                    inactiveTrackColor = BridgeButtonBorder,
                    disabledThumbColor = BridgeMuted,
                    disabledActiveTrackColor = BridgeButtonBorder,
                ),
            )
        }
    }
}

@Composable
private fun DualAxesLandscapePage(
    state: AppUiState,
    canMoveAz: Boolean,
    canMoveEl: Boolean,
    connected: Boolean,
    onAzimuth: (Double) -> Unit,
    onElevation: (Double) -> Unit,
    onStopAz: () -> Unit,
    onStopEl: () -> Unit,
    onSaveFavorite: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LandscapeAxisColumn(
            title = "AZ",
            state = state,
            azimuth = true,
            canMove = canMoveAz,
            connected = connected,
            onPick = onAzimuth,
            onGo = onAzimuth,
            onStop = onStopAz,
            onSaveFavorite = onSaveFavorite,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        LandscapeAxisColumn(
            title = "EL",
            state = state,
            azimuth = false,
            canMove = canMoveEl,
            connected = connected,
            onPick = onElevation,
            onGo = { onElevation(it.coerceIn(0.0, state.rotor.elMaxDeg)) },
            onStop = onStopEl,
            onSaveFavorite = onSaveFavorite,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun LandscapeAxisColumn(
    title: String,
    state: AppUiState,
    azimuth: Boolean,
    canMove: Boolean,
    connected: Boolean,
    onPick: (Double) -> Unit,
    onGo: (Double) -> Unit,
    onStop: () -> Unit,
    onSaveFavorite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var angleDraft by remember { mutableStateOf("") }
    var showSaveDialog by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val online = if (azimuth) state.rotor.azOnline else state.rotor.elOnline
    val offline = !state.rotor.connected || !online
    val ist = if (azimuth) {
        state.rotor.displayAz(state.rotor.azSmoothDeg ?: state.rotor.azDeg)
    } else {
        state.rotor.elSmoothDeg ?: state.rotor.elDeg
    }
    val soll = if (azimuth) {
        state.rotor.azSollDisplay()
    } else {
        state.rotor.elSollDeg
    }
    val elMax = state.rotor.elMaxDeg
    val endstop = state.rotor.selectedOffsetDeg.toFloat()

    fun submit() {
        val deg = angleDraft.trim().replace(',', '.').toDoubleOrNull() ?: return
        if (azimuth) {
            var wrapped = deg % 360.0
            if (wrapped < 0) wrapped += 360.0
            onGo(wrapped)
        } else {
            onGo(deg.coerceIn(0.0, elMax))
        }
        focusManager.clearFocus()
    }

    Column(
        modifier = modifier.padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Kompakte Ist/Soll-Zeile statt großer Card
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = BridgeAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                text = buildAnnotatedString {
                    append("Ist ")
                    withStyle(SpanStyle(color = BridgeIst, fontWeight = FontWeight.SemiBold)) {
                        append(if (offline) "NA" else ist?.let { "%.1f°".format(it) } ?: "NA")
                    }
                    append("  Soll ")
                    withStyle(SpanStyle(color = BridgeSoll, fontWeight = FontWeight.SemiBold)) {
                        append(if (offline) "NA" else soll?.let { "%.1f°".format(it) } ?: "NA")
                    }
                },
                color = BridgeText,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (azimuth) {
                val ant = state.rotor.antennas.getOrNull(state.rotor.selectedAntenna - 1)
                AzimuthCompass(
                    currentDeg = if (online) ist else null,
                    targetDeg = if (online) soll else null,
                    onPick = onPick,
                    enabled = canMove,
                    endstopDeg = endstop,
                    beamOpeningDeg = ant?.openingDeg?.toFloat() ?: 0f,
                    beamColor = antennaBeamColor(state.rotor.selectedAntenna),
                    showBeamOverlay = state.showBeamOverlay,
                    beamDipole = ant?.dipole == true,
                    stromBins36 = state.rotor.stromBins36,
                    showStromRing = state.showStromRing,
                    stromHeatmapScale = state.stromHeatmapScale,
                    dwellSeconds = state.rotor.dwellSeconds,
                    showDwellRing = state.showDwellRing,
                    dwellFullSeconds = state.dwellFullMinutes * 60f,
                    windDirDeg = if (state.activeProfile?.enableWind == true) {
                        state.rotor.windDirDeg?.toFloat()
                    } else {
                        null
                    },
                    windDirMode = state.windDirMode,
                    hudTopLeft = azimuthHudTopLeft(state),
                    hudTopRight = azimuthHudTopRight(state),
                    hudBottomLeft = azimuthHudBottomLeft(state),
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f),
                )
            } else {
                ElevationCompass(
                    currentDeg = if (online) ist else null,
                    targetDeg = if (online) soll else null,
                    onPick = onPick,
                    enabled = canMove,
                    maxDeg = elMax,
                    stromBins36 = state.rotor.stromBinsEl36,
                    showStromRing = state.showStromRing,
                    stromHeatmapScale = state.stromHeatmapScale,
                    hudTopRight = elevationHudTopRight(state),
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }

        // STOP | Winkel | GO
        val controlH = 40.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(controlH),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BridgeButton(
                text = "STOP",
                onClick = onStop,
                enabled = connected,
                tone = BridgeButtonTone.Stop,
                compact = true,
                modifier = Modifier.fillMaxHeight(),
            )
            BasicTextField(
                value = angleDraft,
                onValueChange = { raw ->
                    angleDraft = raw.filter { it.isDigit() || it == '.' || it == ',' || it == '-' }
                },
                enabled = canMove,
                singleLine = true,
                textStyle = TextStyle(
                    color = if (canMove) BridgeText else BridgeMuted,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .border(1.dp, BridgeButtonBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (angleDraft.isEmpty()) {
                            Text(
                                if (azimuth) "Winkel °" else "Elevation °",
                                color = BridgeMuted,
                                fontSize = 13.sp,
                            )
                        }
                        inner()
                    }
                },
            )
            BridgeButton(
                text = "GO",
                onClick = { submit() },
                enabled = canMove && angleDraft.isNotBlank(),
                compact = true,
                modifier = Modifier.fillMaxHeight(),
            )
            BridgeButton(
                text = "SAVE",
                onClick = { showSaveDialog = true },
                compact = true,
                modifier = Modifier.fillMaxHeight(),
            )
        }
        if (showSaveDialog) {
            SaveFavoriteNameDialog(
                onDismiss = { showSaveDialog = false },
                onConfirm = { name ->
                    onSaveFavorite(name)
                    showSaveDialog = false
                },
            )
        }
    }
}

@Composable
private fun AzimuthPage(
    state: AppUiState,
    canMove: Boolean,
    connected: Boolean,
    onAzimuth: (Double) -> Unit,
    onStop: () -> Unit,
    onSaveFavorite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var angleDraft by remember { mutableStateOf("") }
    var showSaveDialog by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun goAngle() {
        val deg = angleDraft.trim().replace(',', '.').toDoubleOrNull() ?: return
        var wrapped = deg % 360.0
        if (wrapped < 0) wrapped += 360.0
        onAzimuth(wrapped)
        focusManager.clearFocus()
    }

    val istDisplay = state.rotor.displayAz(state.rotor.azSmoothDeg ?: state.rotor.azDeg)
    val sollDisplay = state.rotor.azSollDisplay()
    val endstop = state.rotor.selectedOffsetDeg.toFloat()
    val ant = state.rotor.antennas.getOrNull(state.rotor.selectedAntenna - 1)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusCardAz(state)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AzimuthCompass(
                currentDeg = if (state.rotor.azOnline) istDisplay else null,
                targetDeg = if (state.rotor.azOnline) sollDisplay else null,
                onPick = onAzimuth,
                enabled = canMove,
                endstopDeg = endstop,
                beamOpeningDeg = ant?.openingDeg?.toFloat() ?: 0f,
                beamColor = antennaBeamColor(state.rotor.selectedAntenna),
                showBeamOverlay = state.showBeamOverlay,
                beamDipole = ant?.dipole == true,
                stromBins36 = state.rotor.stromBins36,
                showStromRing = state.showStromRing,
                stromHeatmapScale = state.stromHeatmapScale,
                dwellSeconds = state.rotor.dwellSeconds,
                showDwellRing = state.showDwellRing,
                dwellFullSeconds = state.dwellFullMinutes * 60f,
                windDirDeg = if (state.activeProfile?.enableWind == true) {
                    state.rotor.windDirDeg?.toFloat()
                } else {
                    null
                },
                windDirMode = state.windDirMode,
                hudTopLeft = azimuthHudTopLeft(state),
                hudTopRight = azimuthHudTopRight(state),
                hudBottomLeft = azimuthHudBottomLeft(state),
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BridgeButton(
                text = "STOP",
                onClick = onStop,
                enabled = connected,
                tone = BridgeButtonTone.Stop,
                compact = true,
                modifier = Modifier.fillMaxHeight(),
            )
            AngleField(
                value = angleDraft,
                onValueChange = { angleDraft = it },
                enabled = canMove,
                onGo = { goAngle() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            BridgeButton(
                text = "GO",
                onClick = { goAngle() },
                enabled = canMove && angleDraft.isNotBlank(),
                compact = true,
                modifier = Modifier.fillMaxHeight(),
            )
            BridgeButton(
                text = "SAVE",
                onClick = { showSaveDialog = true },
                compact = true,
                modifier = Modifier.fillMaxHeight(),
            )
        }
        if (showSaveDialog) {
            SaveFavoriteNameDialog(
                onDismiss = { showSaveDialog = false },
                onConfirm = { name ->
                    onSaveFavorite(name)
                    showSaveDialog = false
                },
            )
        }
    }
}

@Composable
private fun ElevationPage(
    state: AppUiState,
    canMove: Boolean,
    connected: Boolean,
    onElevation: (Double) -> Unit,
    onStop: () -> Unit,
    onSaveFavorite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var angleDraft by remember { mutableStateOf("") }
    var showSaveDialog by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun goAngle() {
        val deg = angleDraft.trim().replace(',', '.').toDoubleOrNull() ?: return
        onElevation(deg.coerceIn(0.0, state.rotor.elMaxDeg))
        focusManager.clearFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusCardEl(state)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            ElevationCompass(
                currentDeg = if (state.rotor.elOnline) {
                    state.rotor.elSmoothDeg ?: state.rotor.elDeg
                } else {
                    null
                },
                targetDeg = if (state.rotor.elOnline) state.rotor.elSollDeg else null,
                onPick = onElevation,
                enabled = canMove,
                maxDeg = state.rotor.elMaxDeg,
                stromBins36 = state.rotor.stromBinsEl36,
                showStromRing = state.showStromRing,
                stromHeatmapScale = state.stromHeatmapScale,
                hudTopRight = elevationHudTopRight(state),
                modifier = Modifier.fillMaxHeight(),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BridgeButton(
                text = "STOP",
                onClick = onStop,
                enabled = connected,
                tone = BridgeButtonTone.Stop,
                compact = true,
                modifier = Modifier.fillMaxHeight(),
            )
            AngleField(
                value = angleDraft,
                onValueChange = { angleDraft = it },
                enabled = canMove,
                label = "Elevation °",
                onGo = { goAngle() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            BridgeButton(
                text = "GO",
                onClick = { goAngle() },
                enabled = canMove && angleDraft.isNotBlank(),
                compact = true,
                modifier = Modifier.fillMaxHeight(),
            )
            BridgeButton(
                text = "SAVE",
                onClick = { showSaveDialog = true },
                compact = true,
                modifier = Modifier.fillMaxHeight(),
            )
        }
        if (showSaveDialog) {
            SaveFavoriteNameDialog(
                onDismiss = { showSaveDialog = false },
                onConfirm = { name ->
                    onSaveFavorite(name)
                    showSaveDialog = false
                },
            )
        }
    }
}

@Composable
private fun SaveFavoriteNameDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Favorit speichern", color = BridgeText) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = BridgeText,
                    unfocusedTextColor = BridgeText,
                    focusedBorderColor = BridgeAccent,
                    unfocusedBorderColor = BridgeButtonBorder,
                    focusedLabelColor = BridgeMuted,
                    unfocusedLabelColor = BridgeMuted,
                    cursorColor = BridgeAccent,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text("Speichern", color = BridgeAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen", color = BridgeMuted)
            }
        },
        containerColor = BridgePanel,
    )
}

private fun formatFavoriteLabel(fav: PositionFavorite, elevationEnabled: Boolean): String {
    return if (elevationEnabled) {
        "%s · AZ %.1f° / EL %.1f°".format(fav.name, fav.azDeg, fav.elDeg)
    } else {
        "%s · AZ %.1f°".format(fav.name, fav.azDeg)
    }
}

@Composable
private fun StatusCardAz(state: AppUiState) {
    val offline = !state.rotor.connected || !state.rotor.azOnline
    val ist = state.rotor.displayAz(state.rotor.azSmoothDeg ?: state.rotor.azDeg)
    val soll = state.rotor.azSollDisplay()
    Card(
        colors = CardDefaults.cardColors(containerColor = BridgePanel),
        border = BorderStroke(1.dp, BridgeButtonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = buildAnnotatedString {
                append("AZ Ist: ")
                withStyle(SpanStyle(color = BridgeIst, fontWeight = FontWeight.SemiBold)) {
                    append(
                        when {
                            offline -> "NA"
                            else -> ist?.let { "%.1f°".format(it) } ?: "NA"
                        },
                    )
                }
                append("  Soll: ")
                withStyle(SpanStyle(color = BridgeSoll, fontWeight = FontWeight.SemiBold)) {
                    append(
                        when {
                            offline -> "NA"
                            else -> soll?.let { "%.1f°".format(it) } ?: "NA"
                        },
                    )
                }
                when {
                    offline -> Unit
                    state.rotor.azHoming -> append("  · Homing")
                    state.rotor.moving -> append("  · fährt")
                }
            },
            style = MaterialTheme.typography.bodyLarge,
            color = BridgeText,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun StatusCardEl(state: AppUiState) {
    val offline = !state.rotor.connected || !state.rotor.elOnline
    Card(
        colors = CardDefaults.cardColors(containerColor = BridgePanel),
        border = BorderStroke(1.dp, BridgeButtonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = buildAnnotatedString {
                append("EL Ist: ")
                withStyle(SpanStyle(color = BridgeIst, fontWeight = FontWeight.SemiBold)) {
                    append(
                        when {
                            offline -> "NA"
                            else -> state.rotor.elSmoothDeg?.let { "%.1f°".format(it) }
                                ?: state.rotor.elDeg?.let { "%.1f°".format(it) }
                                ?: "NA"
                        },
                    )
                }
                append("  Soll: ")
                withStyle(SpanStyle(color = BridgeSoll, fontWeight = FontWeight.SemiBold)) {
                    append(
                        when {
                            offline -> "NA"
                            else -> state.rotor.elSollDeg?.let { "%.1f°".format(it) } ?: "NA"
                        },
                    )
                }
                if (!offline && state.rotor.elHoming) append("  · Homing")
            },
            style = MaterialTheme.typography.bodyLarge,
            color = BridgeText,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun AngleField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Winkel °",
) {
    BasicTextField(
        value = value,
        onValueChange = { raw ->
            onValueChange(raw.filter { it.isDigit() || it == '.' || it == ',' || it == '-' })
        },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = if (enabled) BridgeText else BridgeMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = modifier
            .height(40.dp)
            .border(1.dp, BridgeButtonBorder, RoundedCornerShape(4.dp))
            .background(BridgePanel, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 0.dp),
        decorationBox = { inner ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        label,
                        color = BridgeMuted,
                        fontSize = 13.sp,
                    )
                }
                inner()
            }
        },
    )
}
