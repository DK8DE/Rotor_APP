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
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import de.dk8de.rotorapp.R
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val PAGE_QUICK = 0
private const val PAGE_AZ = 1
private const val PAGE_EL = 2

/** Logische Navigation — überlebt Drehen unabhängig vom Pager-Index. */
private const val NAV_QUICK = 0
private const val NAV_AZ = 1
private const val NAV_EL = 2
private const val NAV_MAP = 3

private fun navToPage(nav: Int, pageCount: Int, mapPageIndex: Int, dualAxes: Boolean): Int = when (nav) {
    NAV_QUICK -> 0
    NAV_MAP -> mapPageIndex
    NAV_EL -> when {
        dualAxes -> 1
        mapPageIndex > PAGE_EL -> PAGE_EL // eigene EL-Seite
        else -> 1
    }
    else -> 1 // AZ bzw. AZ/EL im Querformat
}.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

/** @param dualAxes wenn true und Seite 1: null = AZ/EL gemeinsam, vorherigen AZ/EL-Nav behalten */
private fun pageToNav(page: Int, mapPageIndex: Int, dualAxes: Boolean): Int? = when {
    page <= 0 -> NAV_QUICK
    page >= mapPageIndex -> NAV_MAP
    dualAxes -> null // AZ+EL-Kombi — AZ/EL-Unterscheidung nicht überschreiben
    page == PAGE_EL -> NAV_EL
    else -> NAV_AZ
}
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
    val navQuick = stringResource(R.string.nav_quick)
    val navAzEl = stringResource(R.string.nav_az_el)
    val navAz = stringResource(R.string.nav_az)
    val navEl = stringResource(R.string.nav_el)
    val navMap = stringResource(R.string.nav_map)
    val pageLabels = when {
        dualAxes -> listOf(navQuick, navAzEl, navMap)
        elevationEnabled -> listOf(navQuick, navAz, navEl, navMap)
        else -> listOf(navQuick, navAz, navMap)
    }
    val pageCount = pageLabels.size
    val mapPageIndex = pageLabels.lastIndex
    // AZ vs EL getrennt merken — sonst landet man nach Quer→Hochkant immer auf AZ
    var navSection by rememberSaveable { mutableIntStateOf(NAV_AZ) }
    val pagerState = remember(pageCount, dualAxes) {
        PagerState(
            currentPage = navToPage(navSection, pageCount, mapPageIndex, dualAxes),
            pageCount = { pageCount },
        )
    }
    val scope = rememberCoroutineScope()
    val onMapPage = pagerState.currentPage == mapPageIndex
    // Karte nach erstem Besuch behalten (WebView nicht neu aufbauen)
    var mapCached by remember { mutableStateOf(false) }
    LaunchedEffect(onMapPage) {
        if (onMapPage) mapCached = true
    }

    LaunchedEffect(pagerState, mapPageIndex, dualAxes) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val mapped = pageToNav(page, mapPageIndex, dualAxes)
                if (mapped != null) {
                    navSection = mapped
                }
            }
    }

    LaunchedEffect(pageCount, dualAxes, navSection) {
        val target = navToPage(navSection, pageCount, mapPageIndex, dualAxes)
        if (pagerState.currentPage != target) {
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
                        text = stringResource(R.string.app_name),
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
                            contentDescription = stringResource(R.string.cd_settings),
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
            title = { Text(stringResource(R.string.dialog_delete_favorite_title), color = BridgeText) },
            text = {
                Text(
                    stringResource(R.string.dialog_delete_favorite_message, selectedFav.name),
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
                    Text(stringResource(R.string.action_yes), color = BridgeStop)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_no), color = BridgeMuted)
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
            enabled = connected && state.rotor.azOnline,
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
                Text(stringResource(R.string.favorites_title), color = BridgeText, fontWeight = FontWeight.SemiBold)
                ExposedDropdownMenuBox(
                    expanded = favExpanded,
                    onExpandedChange = { favExpanded = it && favorites.isNotEmpty() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextField(
                        value = selectedFav?.let { formatFavoriteLabel(it, elevationEnabled) }
                            ?: if (favorites.isEmpty()) {
                                stringResource(R.string.favorites_empty)
                } else {
                                stringResource(R.string.favorites_select)
                            },
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
                        text = stringResource(R.string.action_go),
                        onClick = { selectedFavId?.let(onGoFavorite) },
                        enabled = connected && selectedFavId != null &&
                            state.rotor.azReferenced && !state.rotor.azHoming,
                        compact = true,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    BridgeButton(
                        text = stringResource(R.string.action_del),
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
            title = stringResource(R.string.pwm_title_az),
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
                title = stringResource(R.string.pwm_title_el),
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
                Text(stringResource(R.string.stats_reset_heading), color = BridgeText, fontWeight = FontWeight.SemiBold)
                BridgeButton(
                    text = stringResource(R.string.btn_reset_dwell),
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
            text = stringResource(R.string.btn_park),
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
                text = stringResource(R.string.btn_home_az),
                onClick = onHomeAz,
                enabled = connected && !state.rotor.azHoming,
                compact = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            if (elevationEnabled) {
                BridgeButton(
                    text = stringResource(R.string.btn_home_el),
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
    return stringResource(R.string.hud_outdoor) to v
}

@Composable
private fun azimuthHudTopRight(state: AppUiState): List<Pair<String, String>> {
    val connected = state.rotor.connected
    fun t(v: Double?): String =
        if (!connected) "—" else v?.let { "%.1f°C".format(it) } ?: "—"
    val label = if (state.activeProfile?.enableEl == true) {
        stringResource(R.string.hud_motor_az)
    } else {
        stringResource(R.string.hud_motor)
    }
    return listOf(label to t(state.rotor.tempMotorAzC))
}

@Composable
private fun elevationHudTopRight(state: AppUiState): Pair<String, String> {
    val connected = state.rotor.connected
    val v = if (!connected) "—" else state.rotor.tempMotorElC?.let { "%.1f°C".format(it) } ?: "—"
    return stringResource(R.string.hud_motor_el) to v
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
    return stringResource(R.string.hud_wind) to windVal
}

@Composable
private fun AntennaSelectCard(
    antennas: List<de.dk8de.rotorapp.rotor.AntennaSlot>,
    selected: Int,
    enabled: Boolean,
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
                stringResource(R.string.antenna_section),
                color = BridgeText,
                        fontWeight = FontWeight.SemiBold,
                    )
            when {
                !connected -> Text(
                    stringResource(R.string.antenna_connect_hint),
                    color = BridgeMuted,
                    fontSize = 13.sp,
                )
                !enabled -> Text(
                    stringResource(R.string.antenna_az_required_hint),
                    color = BridgeMuted,
                    fontSize = 13.sp,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..3).forEach { slot ->
                    val ant = antennas.getOrNull(slot - 1)
                    val fallback = stringResource(R.string.antenna_fallback, slot)
                    val label = ant?.label(slot, fallback) ?: "$fallback (0°)"
                    val isSel = selected == slot
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSel && enabled) BridgeAccent.copy(alpha = 0.18f) else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSel && enabled) BridgeAccent else BridgeButtonBorder,
                                RoundedCornerShape(6.dp),
                            )
                            .then(
                                if (enabled) {
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
                            color = if (enabled) BridgeText else BridgeMuted,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSel && enabled) {
                            Text(stringResource(R.string.antenna_active), color = BridgeAccent, fontSize = 12.sp)
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
            title = stringResource(R.string.nav_az),
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
            title = stringResource(R.string.nav_el),
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
    val na = stringResource(R.string.value_na)
    val compactIst = stringResource(R.string.compact_ist)
    val compactSoll = stringResource(R.string.compact_soll)
    val labelAngle = stringResource(R.string.label_angle_deg)
    val labelElevation = stringResource(R.string.label_elevation_deg)

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
                // Abstände hier, nicht in den Strings: aapt trimmt Leerzeichen in Resources.
                text = buildAnnotatedString {
                    append(compactIst)
                    append(' ')
                    withStyle(SpanStyle(color = BridgeIst, fontWeight = FontWeight.SemiBold)) {
                        append(if (offline) na else ist?.let { "%.1f°".format(it) } ?: na)
                    }
                    append("   ")
                    append(compactSoll)
                    append(' ')
                    withStyle(SpanStyle(color = BridgeSoll, fontWeight = FontWeight.SemiBold)) {
                        append(if (offline) na else soll?.let { "%.1f°".format(it) } ?: na)
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
                text = stringResource(R.string.action_stop),
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
                                if (azimuth) labelAngle else labelElevation,
                                color = BridgeMuted,
                                fontSize = 13.sp,
                            )
                        }
                        inner()
                    }
                },
            )
            BridgeButton(
                text = stringResource(R.string.action_go),
                onClick = { submit() },
                enabled = canMove && angleDraft.isNotBlank(),
                compact = true,
                modifier = Modifier.fillMaxHeight(),
            )
            BridgeButton(
                text = stringResource(R.string.action_save),
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
    val labelAngle = stringResource(R.string.label_angle_deg)

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
                text = stringResource(R.string.action_stop),
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
                label = labelAngle,
                onGo = { goAngle() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            BridgeButton(
                text = stringResource(R.string.action_go),
                onClick = { goAngle() },
                enabled = canMove && angleDraft.isNotBlank(),
                compact = true,
                modifier = Modifier.fillMaxHeight(),
            )
            BridgeButton(
                text = stringResource(R.string.action_save),
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

    val labelElevation = stringResource(R.string.label_elevation_deg)

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
                text = stringResource(R.string.action_stop),
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
                label = labelElevation,
                onGo = { goAngle() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            BridgeButton(
                text = stringResource(R.string.action_go),
                onClick = { goAngle() },
                enabled = canMove && angleDraft.isNotBlank(),
                compact = true,
                modifier = Modifier.fillMaxHeight(),
            )
            BridgeButton(
                text = stringResource(R.string.action_save),
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
        title = { Text(stringResource(R.string.dialog_save_favorite_title), color = BridgeText) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.field_name)) },
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
                Text(stringResource(R.string.action_save_label), color = BridgeAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = BridgeMuted)
            }
        },
        containerColor = BridgePanel,
    )
}

@Composable
private fun formatFavoriteLabel(fav: PositionFavorite, elevationEnabled: Boolean): String {
    return if (elevationEnabled) {
        stringResource(R.string.favorite_label_az_el, fav.name, fav.azDeg, fav.elDeg)
    } else {
        stringResource(R.string.favorite_label_az, fav.name, fav.azDeg)
    }
}

@Composable
private fun StatusCardAz(state: AppUiState) {
    val offline = !state.rotor.connected || !state.rotor.azOnline
    val ist = state.rotor.displayAz(state.rotor.azSmoothDeg ?: state.rotor.azDeg)
    val soll = state.rotor.azSollDisplay()
    val na = stringResource(R.string.value_na)
    val suffixHoming = stringResource(R.string.status_suffix_homing)
    Card(
        colors = CardDefaults.cardColors(containerColor = BridgePanel),
        border = BorderStroke(1.dp, BridgeButtonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = buildAnnotatedString {
                append(stringResource(R.string.status_az_ist))
                append(' ')
                withStyle(SpanStyle(color = BridgeIst, fontWeight = FontWeight.SemiBold)) {
                    append(
                        when {
                            offline -> na
                            else -> ist?.let { "%.1f°".format(it) } ?: na
                        },
                    )
                }
                append("   ")
                append(stringResource(R.string.status_soll))
                append(' ')
                withStyle(SpanStyle(color = BridgeSoll, fontWeight = FontWeight.SemiBold)) {
                    append(
                        when {
                            offline -> na
                            else -> soll?.let { "%.1f°".format(it) } ?: na
                        },
                    )
                }
                // Fahrt selbst zeigt der Kompass — hier nur Homing als Zusatz.
                if (!offline && state.rotor.azHoming) append("   $suffixHoming")
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
    val na = stringResource(R.string.value_na)
    val suffixHoming = stringResource(R.string.status_suffix_homing)
    Card(
        colors = CardDefaults.cardColors(containerColor = BridgePanel),
        border = BorderStroke(1.dp, BridgeButtonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = buildAnnotatedString {
                append(stringResource(R.string.status_el_ist))
                append(' ')
                withStyle(SpanStyle(color = BridgeIst, fontWeight = FontWeight.SemiBold)) {
                    append(
                        when {
                            offline -> na
                            else -> state.rotor.elSmoothDeg?.let { "%.1f°".format(it) }
                                ?: state.rotor.elDeg?.let { "%.1f°".format(it) }
                                ?: na
                        },
                    )
                }
                append("   ")
                append(stringResource(R.string.status_soll))
                append(' ')
                withStyle(SpanStyle(color = BridgeSoll, fontWeight = FontWeight.SemiBold)) {
                    append(
                        when {
                            offline -> na
                            else -> state.rotor.elSollDeg?.let { "%.1f°".format(it) } ?: na
                        },
                    )
                }
                if (!offline && state.rotor.elHoming) append("   $suffixHoming")
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
    label: String = "",
) {
    val defaultLabel = if (label.isEmpty()) stringResource(R.string.label_angle_deg) else label
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
                        defaultLabel,
                        color = BridgeMuted,
                        fontSize = 13.sp,
                    )
                }
                inner()
            }
        },
    )
}
