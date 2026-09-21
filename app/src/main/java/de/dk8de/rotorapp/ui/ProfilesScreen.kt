package de.dk8de.rotorapp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import de.dk8de.rotorapp.AppLanguage
import de.dk8de.rotorapp.AppVersion
import de.dk8de.rotorapp.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dk8de.rotorapp.data.RotorProfile
import de.dk8de.rotorapp.rotor.AntennaSlot
import de.dk8de.rotorapp.ui.components.BridgeButton
import de.dk8de.rotorapp.ui.theme.BridgeAccent
import de.dk8de.rotorapp.ui.theme.BridgeBg
import de.dk8de.rotorapp.ui.theme.BridgeIst
import de.dk8de.rotorapp.ui.theme.BridgeMuted
import de.dk8de.rotorapp.ui.theme.BridgePanel
import de.dk8de.rotorapp.ui.theme.BridgeText
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onActivate: (String) -> Unit,
    onSave: (RotorProfile) -> Unit,
    onDelete: (String) -> Unit,
    onSaveAntenna: (Int, AntennaSlot) -> Unit,
    onRefreshAntennas: () -> Unit,
    onShowBeamOverlayChange: (Boolean) -> Unit,
    onShowStromRingChange: (Boolean) -> Unit,
    onShowDwellRingChange: (Boolean) -> Unit,
    onDwellFullMinutesChange: (Float) -> Unit,
    onDwellSectorsChange: (Int) -> Unit,
    onHeatmapCustomChange: (Boolean) -> Unit,
    onHeatmapScaleSave: (custom: Boolean, thrBlue: Int, normMin: Int, normMax: Int, thrRed: Int) -> Unit,
    onHeatmapFromBins: () -> Unit,
    onLocationSave: (lat: Double, lon: Double, locator: String) -> Unit,
    onAppLanguageChange: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<RotorProfile?>(null) }
    var showAbout by remember { mutableStateOf(false) }

    LaunchedEffect(state.rotor.connected) {
        if (state.rotor.connected) onRefreshAntennas()
    }

    if (editing != null) {
        ProfileEditor(
            initial = editing!!,
            onCancel = { editing = null },
            onSave = {
                onSave(it)
                editing = null
            },
        )
        return
    }

    val newProfileName = stringResource(R.string.profile_new_name)

    Scaffold(
        containerColor = BridgeBg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BridgePanel,
                    titleContentColor = BridgeText,
                    navigationIconContentColor = BridgeText,
                    actionIconContentColor = BridgeText,
                ),
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editing = RotorProfile(id = UUID.randomUUID().toString(), name = newProfileName)
                },
                containerColor = BridgeAccent,
                contentColor = BridgeBg,
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // —— 1. Profile ——
            item {
                Text(
                    stringResource(R.string.section_profiles),
                    color = BridgeAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            items(state.profiles, key = { it.id }) { profile ->
                val active = profile.id == state.activeProfile?.id
                val summary = profile.summary(
                    azElLabel = stringResource(R.string.profile_summary_az_el),
                    azOnlyLabel = stringResource(R.string.profile_summary_az_only),
                    windSuffix = stringResource(R.string.profile_summary_wind),
                )
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = BridgeBg),
                    headlineContent = { Text(profile.name, color = BridgeText) },
                    supportingContent = {
                        Text(
                            if (active) {
                                stringResource(R.string.profile_active, summary)
                            } else {
                                summary
                            },
                            color = if (active) BridgeIst else BridgeText.copy(alpha = 0.7f),
                        )
                    },
                    leadingContent = {
                        RadioButton(
                            selected = active,
                            onClick = { onActivate(profile.id) },
                        )
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = profile }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cd_edit), tint = BridgeText)
                            }
                            IconButton(onClick = { onDelete(profile.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete), tint = BridgeText)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onActivate(profile.id) },
                )
            }

            // —— Sprache ——
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                LanguageSection(
                    selected = state.displayPrefs.appLanguage,
                    onSelect = onAppLanguageChange,
                )
            }

            // —— 2. Antennen ——
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.section_antennas),
                    color = BridgeAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                if (!state.rotor.connected) {
                    Text(
                        stringResource(R.string.antennas_connect_hint),
                        color = BridgeMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                } else if (!state.rotor.azOnline) {
                    Text(
                        stringResource(R.string.antennas_az_required_hint),
                        color = BridgeMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
            items(3) { index ->
                val slot = index + 1
                val ant = state.rotor.antennas.getOrNull(index) ?: AntennaSlot()
                AntennaEditorCard(
                    slot = slot,
                    initial = ant,
                    enabled = state.rotor.connected && state.rotor.azOnline,
                    selected = state.rotor.selectedAntenna == slot,
                    onSave = { onSaveAntenna(slot, it) },
                )
            }

            // —— 3. Kompass-Anzeige ——
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.section_compass_display),
                    color = BridgeAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Text(
                    stringResource(R.string.compass_rings_hint),
                    color = BridgeMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
                SettingsCheckRow(
                    checked = state.showStromRing,
                    onChange = onShowStromRingChange,
                    title = stringResource(R.string.toggle_strom_ring),
                    subtitle = stringResource(R.string.toggle_strom_ring_sub),
                )
                HeatmapScaleEditor(
                    prefs = state.displayPrefs,
                    hasBins = state.rotor.stromBins36?.any { it > 0 } == true ||
                        state.rotor.stromBinsEl36?.any { it > 0 } == true,
                    onCustomChange = onHeatmapCustomChange,
                    onSave = onHeatmapScaleSave,
                    onFromBins = onHeatmapFromBins,
                )
                SettingsCheckRow(
                    checked = state.showDwellRing,
                    onChange = onShowDwellRingChange,
                    title = stringResource(R.string.toggle_dwell_ring),
                    subtitle = stringResource(R.string.toggle_dwell_ring_sub),
                )
                DwellMinutesEditor(
                    minutes = state.dwellFullMinutes,
                    onSave = onDwellFullMinutesChange,
                )
                DwellSectorsEditor(
                    sectors = state.dwellSectors,
                    onSave = onDwellSectorsChange,
                )
            }

            // —— 4. Öffnungswinkel (unter Kompass-Anzeige) ——
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.section_beam_overlay),
                    color = BridgeAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Text(
                    stringResource(R.string.beam_overlay_hint),
                    color = BridgeMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
                SettingsCheckRow(
                    checked = state.showBeamOverlay,
                    onChange = onShowBeamOverlayChange,
                    title = stringResource(R.string.toggle_beam_overlay),
                    subtitle = stringResource(R.string.toggle_beam_overlay_sub),
                )
            }

            // —— Standort (Karte) ——
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.section_location),
                    color = BridgeAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Text(
                    stringResource(R.string.location_qth_hint),
                    color = BridgeMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
                LocationEditor(
                    lat = state.displayPrefs.locationLat,
                    lon = state.displayPrefs.locationLon,
                    locator = state.displayPrefs.locationLocator,
                    onSave = onLocationSave,
                )
            }

            // —— Info / Version ——
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.section_about),
                    color = BridgeAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                BridgeButton(
                    text = stringResource(R.string.btn_version),
                    onClick = { showAbout = true },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                )
            }

            // —— Ende Einstellungen ——
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

/** Übliche „Über die App“-Box: Version, Autor/Rufzeichen, Lizenz mit Link. */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val licenseUrl = stringResource(R.string.about_license_url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.app_name),
                color = BridgeText,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AboutRow(stringResource(R.string.about_version), AppVersion.NAME)
                AboutRow(stringResource(R.string.about_developer), stringResource(R.string.about_author))
                AboutRow(
                    stringResource(R.string.about_callsign),
                    stringResource(R.string.about_callsign_value),
                )
                AboutRow(
                    stringResource(R.string.about_license),
                    stringResource(R.string.about_license_value),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    stringResource(R.string.about_license_notice),
                    color = BridgeMuted,
                    fontSize = 12.sp,
                )
                Text(
                    licenseUrl,
                    color = BridgeAccent,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(licenseUrl)),
                            )
                        }
                    },
                )
                Text(
                    stringResource(R.string.about_copyright),
                    color = BridgeMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = BridgeAccent)
            }
        },
        containerColor = BridgePanel,
    )
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = BridgeMuted,
            fontSize = 13.sp,
            modifier = Modifier.width(110.dp),
        )
        Text(value, color = BridgeText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LocationEditor(
    lat: Double,
    lon: Double,
    locator: String,
    onSave: (lat: Double, lon: Double, locator: String) -> Unit,
) {
    var latText by remember(lat) { mutableStateOf("%.6f".format(lat)) }
    var lonText by remember(lon) { mutableStateOf("%.6f".format(lon)) }
    var locText by remember(locator) { mutableStateOf(locator) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        ProfileField(
            value = latText,
            onValueChange = {
                latText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' || ch == '-' }
            },
            label = stringResource(R.string.field_latitude),
            keyboardType = KeyboardType.Decimal,
            keyboardActions = KeyboardActions(),
        )
        ProfileField(
            value = lonText,
            onValueChange = {
                lonText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' || ch == '-' }
            },
            label = stringResource(R.string.field_longitude),
            keyboardType = KeyboardType.Decimal,
            keyboardActions = KeyboardActions(),
        )
        ProfileField(
            value = locText,
            onValueChange = { locText = it.uppercase().filter { ch -> ch.isLetterOrDigit() } },
            label = stringResource(R.string.field_maidenhead),
            keyboardActions = KeyboardActions(),
        )
        BridgeButton(
            text = stringResource(R.string.btn_save_location),
            onClick = {
                val loc = locText.trim()
                val fromLoc = de.dk8de.rotorapp.geo.GeoUtils.maidenheadToLatLon(loc)
                val finalLat: Double
                val finalLon: Double
                if (fromLoc != null && loc.isNotEmpty()) {
                    finalLat = fromLoc.first
                    finalLon = fromLoc.second
                    latText = "%.6f".format(finalLat)
                    lonText = "%.6f".format(finalLon)
                } else {
                    finalLat = latText.replace(',', '.').toDoubleOrNull() ?: lat
                    finalLon = lonText.replace(',', '.').toDoubleOrNull() ?: lon
                }
                onSave(finalLat, finalLon, loc)
            },
            compact = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun DwellMinutesEditor(
    minutes: Float,
    onSave: (Float) -> Unit,
) {
    var dwellMin by remember(minutes) {
        mutableStateOf(
            if (kotlin.math.abs(minutes - minutes.toInt()) < 0.05f) {
                minutes.toInt().toString()
            } else {
                "%.1f".format(minutes)
            },
        )
    }
    ProfileField(
        value = dwellMin,
        onValueChange = {
            dwellMin = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }
        },
        label = stringResource(R.string.field_dwell_red_minutes),
        keyboardType = KeyboardType.Decimal,
        enabled = true,
        keyboardActions = KeyboardActions(
            onDone = {
                val v = dwellMin.replace(',', '.').toFloatOrNull() ?: 5f
                onSave(v)
            },
        ),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    BridgeButton(
        text = stringResource(R.string.btn_save_dwell_time),
        onClick = {
            val v = dwellMin.replace(',', '.').toFloatOrNull() ?: 5f
            onSave(v)
        },
        compact = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun HeatmapScaleEditor(
    prefs: de.dk8de.rotorapp.data.UiDisplayPrefs,
    hasBins: Boolean,
    onCustomChange: (Boolean) -> Unit,
    onSave: (Boolean, Int, Int, Int, Int) -> Unit,
    onFromBins: () -> Unit,
) {
    var thrBlue by remember(prefs.heatmapThrBlue) { mutableStateOf(prefs.heatmapThrBlue.toString()) }
    var normMin by remember(prefs.heatmapNormMin) { mutableStateOf(prefs.heatmapNormMin.toString()) }
    var normMax by remember(prefs.heatmapNormMax) { mutableStateOf(prefs.heatmapNormMax.toString()) }
    var thrRed by remember(prefs.heatmapThrRed) { mutableStateOf(prefs.heatmapThrRed.toString()) }
    val focus = LocalFocusManager.current

    fun parse(s: String): Int = s.toIntOrNull()?.coerceIn(0, 65535) ?: 0

    Text(
        stringResource(R.string.heatmap_az_heading),
        color = BridgeAccent,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Text(
        stringResource(R.string.heatmap_az_description),
        color = BridgeMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    )
    SettingsCheckRow(
        checked = prefs.heatmapCustom,
        onChange = onCustomChange,
        title = stringResource(R.string.toggle_heatmap_custom),
        subtitle = stringResource(R.string.toggle_heatmap_custom_sub),
    )
    if (prefs.heatmapCustom) {
        val done = KeyboardActions(onDone = { focus.clearFocus() })
        ProfileField(
            value = thrBlue,
            onValueChange = { thrBlue = it.filter { ch -> ch.isDigit() } },
            label = stringResource(R.string.field_thr_blue),
            keyboardType = KeyboardType.Number,
            keyboardActions = done,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ProfileField(
            value = normMin,
            onValueChange = { normMin = it.filter { ch -> ch.isDigit() } },
            label = stringResource(R.string.field_norm_min),
            keyboardType = KeyboardType.Number,
            keyboardActions = done,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ProfileField(
            value = normMax,
            onValueChange = { normMax = it.filter { ch -> ch.isDigit() } },
            label = stringResource(R.string.field_norm_max),
            keyboardType = KeyboardType.Number,
            keyboardActions = done,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ProfileField(
            value = thrRed,
            onValueChange = { thrRed = it.filter { ch -> ch.isDigit() } },
            label = stringResource(R.string.field_thr_red),
            keyboardType = KeyboardType.Number,
            keyboardActions = done,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        BridgeButton(
            text = stringResource(R.string.btn_save_scale),
            onClick = {
                onSave(true, parse(thrBlue), parse(normMin), parse(normMax), parse(thrRed))
            },
            compact = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
        BridgeButton(
            text = stringResource(R.string.btn_fill_from_bins),
            onClick = onFromBins,
            enabled = hasBins,
            compact = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DwellSectorsEditor(
    sectors: Int,
    onSave: (Int) -> Unit,
) {
    var text by remember(sectors) { mutableStateOf(sectors.toString()) }
    ProfileField(
        value = text,
        onValueChange = { text = it.filter { ch -> ch.isDigit() } },
        label = stringResource(R.string.field_dwell_sectors),
        keyboardType = KeyboardType.Number,
        enabled = true,
        keyboardActions = KeyboardActions(
            onDone = {
                val v = text.toIntOrNull()?.coerceIn(10, 100) ?: 20
                text = v.toString()
                onSave(v)
            },
        ),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Text(
        stringResource(R.string.dwell_sectors_hint),
        color = BridgeMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
    BridgeButton(
        text = stringResource(R.string.btn_save_sectors),
        onClick = {
            val v = text.toIntOrNull()?.coerceIn(10, 100) ?: 20
            text = v.toString()
            onSave(v)
        },
        compact = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun WindDirModeRow(
    selected: String,
    onChange: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange("from") }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected != "to",
                onClick = { onChange("from") },
            )
            Column {
                Text(stringResource(R.string.wind_mode_from_title), color = BridgeText)
                Text(stringResource(R.string.wind_mode_from_sub), color = BridgeMuted, fontSize = 12.sp)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChange("to") }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected == "to",
                onClick = { onChange("to") },
            )
            Column {
                Text(stringResource(R.string.wind_mode_to_title), color = BridgeText)
                Text(stringResource(R.string.wind_mode_to_sub), color = BridgeMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingsCheckRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(title, color = BridgeText)
            Text(subtitle, color = BridgeMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AntennaEditorCard(
    slot: Int,
    initial: AntennaSlot,
    enabled: Boolean,
    selected: Boolean,
    onSave: (AntennaSlot) -> Unit,
) {
    var expanded by remember(slot) { mutableStateOf(false) }
    var name by remember(initial.name, slot) { mutableStateOf(initial.name) }
    var offset by remember(initial.offsetDeg, slot) {
        mutableStateOf(formatNum(initial.offsetDeg))
    }
    var opening by remember(initial.openingDeg, slot) {
        mutableStateOf(formatNum(initial.openingDeg))
    }
    var range by remember(initial.rangeKm, slot) {
        mutableStateOf(initial.rangeKm.toString())
    }
    var dipole by remember(initial.dipole, slot) { mutableStateOf(initial.dipole) }

    LaunchedEffect(initial) {
        name = initial.name
        offset = formatNum(initial.offsetDeg)
        opening = formatNum(initial.openingDeg)
        range = initial.rangeKm.toString()
        dipole = initial.dipole
    }

    val unnamed = stringResource(R.string.antenna_unnamed)
    val dipoleSuffix = stringResource(R.string.antenna_dipole_suffix)
    val activeSuffix = stringResource(R.string.antenna_active_suffix)
    val summary = buildString {
        val n = name.trim().ifEmpty { unnamed }
        append(n)
        append(" · ")
        append(offset.ifEmpty { "0" })
        append("°")
        if (dipole) append(" $dipoleSuffix")
        if (selected) append(" $activeSuffix")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.antenna_slot, slot),
                    color = if (selected) BridgeIst else BridgeText,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = summary,
                    color = BridgeMuted,
                    fontSize = 13.sp,
                )
            }
            Icon(
                imageVector = if (expanded) {
                    Icons.Default.KeyboardArrowUp
                } else {
                    Icons.Default.KeyboardArrowDown
                },
                contentDescription = if (expanded) {
                    stringResource(R.string.cd_collapse)
                } else {
                    stringResource(R.string.cd_expand)
                },
                tint = BridgeText,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProfileField(
                    value = name,
                    onValueChange = { name = it.take(9) },
                    label = stringResource(R.string.field_antenna_name),
                    enabled = enabled,
                    keyboardActions = KeyboardActions(),
                )
                ProfileField(
                    value = offset,
                    onValueChange = { offset = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                    label = stringResource(R.string.field_offset_deg),
                    keyboardType = KeyboardType.Decimal,
                    enabled = enabled,
                    keyboardActions = KeyboardActions(),
                )
                ProfileField(
                    value = opening,
                    onValueChange = { opening = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                    label = stringResource(R.string.field_opening_deg),
                    keyboardType = KeyboardType.Decimal,
                    enabled = enabled,
                    keyboardActions = KeyboardActions(),
                )
                ProfileField(
                    value = range,
                    onValueChange = { range = it.filter { ch -> ch.isDigit() } },
                    label = stringResource(R.string.field_range_km),
                    keyboardType = KeyboardType.Number,
                    enabled = enabled,
                    keyboardActions = KeyboardActions(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = dipole,
                        onCheckedChange = { if (enabled) dipole = it },
                        enabled = enabled,
                    )
                    Text(stringResource(R.string.label_dipole), color = if (enabled) BridgeText else BridgeMuted)
                }
                BridgeButton(
                    text = stringResource(R.string.btn_save_profile),
                    onClick = {
                        onSave(
                            AntennaSlot(
                                offsetDeg = offset.replace(',', '.').toDoubleOrNull() ?: 0.0,
                                openingDeg = opening.replace(',', '.').toDoubleOrNull() ?: 0.0,
                                rangeKm = range.toIntOrNull() ?: 100,
                                dipole = dipole,
                                name = name.trim(),
                            ),
                        )
                    },
                    enabled = enabled,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        HorizontalDivider()
    }
}

private fun formatNum(v: Double): String =
    if (kotlin.math.abs(v - v.toInt()) < 0.05) v.toInt().toString()
    else "%.1f".format(v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditor(
    initial: RotorProfile,
    onCancel: () -> Unit,
    onSave: (RotorProfile) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var masterId by remember { mutableStateOf(initial.masterId.toString()) }
    var slaveAz by remember { mutableStateOf(initial.slaveAz.toString()) }
    var slaveEl by remember { mutableStateOf(initial.slaveEl.toString()) }
    var controllerId by remember { mutableStateOf(initial.controllerId.toString()) }
    var enableEl by remember { mutableStateOf(initial.enableEl) }
    var enableWind by remember { mutableStateOf(initial.enableWind) }
    var windDirMode by remember {
        mutableStateOf(if (initial.windDirMode.equals("to", true)) "to" else "from")
    }
    val focusManager = LocalFocusManager.current

    fun commit() {
        focusManager.clearFocus()
        onSave(
            initial.copy(
                name = name.trim().ifEmpty { "Rotor" },
                host = host.trim(),
                port = port.toIntOrNull() ?: 8886,
                masterId = masterId.toIntOrNull() ?: 7,
                slaveAz = slaveAz.toIntOrNull() ?: 20,
                enableEl = enableEl,
                slaveEl = slaveEl.toIntOrNull() ?: 21,
                controllerId = controllerId.toIntOrNull() ?: 2,
                enableWind = enableWind,
                windDirMode = if (enableWind && windDirMode == "to") "to" else "from",
            )
        )
    }

    val saveActions = KeyboardActions(onDone = { commit() })

    Scaffold(
        containerColor = BridgeBg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BridgePanel,
                    titleContentColor = BridgeText,
                    navigationIconContentColor = BridgeText,
                    actionIconContentColor = BridgeAccent,
                ),
                title = { Text(stringResource(R.string.profile_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    BridgeButton(
                        text = stringResource(R.string.btn_save_profile),
                        onClick = { commit() },
                        compact = true,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProfileField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.field_profile_name),
                keyboardActions = saveActions,
            )
            ProfileField(
                value = host,
                onValueChange = { host = it },
                label = stringResource(R.string.field_host),
                keyboardActions = saveActions,
            )
            ProfileField(
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                label = stringResource(R.string.field_port),
                keyboardType = KeyboardType.Number,
                keyboardActions = saveActions,
            )
            ProfileField(
                value = masterId,
                onValueChange = { masterId = it.filter { ch -> ch.isDigit() } },
                label = stringResource(R.string.field_master_id),
                keyboardType = KeyboardType.Number,
                keyboardActions = saveActions,
            )
            ProfileField(
                value = slaveAz,
                onValueChange = { slaveAz = it.filter { ch -> ch.isDigit() } },
                label = stringResource(R.string.field_slave_az),
                keyboardType = KeyboardType.Number,
                keyboardActions = saveActions,
            )
            ProfileField(
                value = controllerId,
                onValueChange = { controllerId = it.filter { ch -> ch.isDigit() } },
                label = stringResource(R.string.field_controller_id),
                keyboardType = KeyboardType.Number,
                keyboardActions = saveActions,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = enableEl, onCheckedChange = { enableEl = it })
                Text(stringResource(R.string.checkbox_elevation))
            }
            if (enableEl) {
                ProfileField(
                    value = slaveEl,
                    onValueChange = { slaveEl = it.filter { ch -> ch.isDigit() } },
                    label = stringResource(R.string.field_slave_el),
                    keyboardType = KeyboardType.Number,
                    keyboardActions = saveActions,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = enableWind, onCheckedChange = { enableWind = it })
                Column {
                    Text(stringResource(R.string.checkbox_wind), color = BridgeText)
                    Text(
                        stringResource(R.string.wind_enabled_hint),
                        color = BridgeMuted,
                        fontSize = 12.sp,
                    )
                }
            }
            if (enableWind) {
                Text(
                    stringResource(R.string.wind_arrow_heading),
                    color = BridgeText,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                WindDirModeRow(
                    selected = windDirMode,
                    onChange = { windDirMode = it },
                )
            }
        }
    }
}

@Composable
private fun LanguageSection(
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(
        stringResource(R.string.section_language),
        color = BridgeAccent,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
    LanguageOptionRow(
        tag = AppLanguage.SYSTEM,
        label = stringResource(R.string.language_system),
        selected = selected,
        onSelect = onSelect,
    )
    LanguageOptionRow(
        tag = AppLanguage.DE,
        label = stringResource(R.string.language_de),
        selected = selected,
        onSelect = onSelect,
    )
    LanguageOptionRow(
        tag = AppLanguage.EN,
        label = stringResource(R.string.language_en),
        selected = selected,
        onSelect = onSelect,
    )
}

@Composable
private fun LanguageOptionRow(
    tag: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(tag) }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected == tag,
            onClick = { onSelect(tag) },
        )
        Text(label, color = BridgeText, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun ProfileField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    keyboardActions: KeyboardActions,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.replace("\n", "")) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = keyboardActions,
        modifier = modifier.fillMaxWidth(),
    )
}
