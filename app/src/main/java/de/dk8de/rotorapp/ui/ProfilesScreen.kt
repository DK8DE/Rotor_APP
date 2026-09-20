package de.dk8de.rotorapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.platform.LocalFocusManager
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
) {
    var editing by remember { mutableStateOf<RotorProfile?>(null) }

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
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editing = RotorProfile(id = UUID.randomUUID().toString(), name = "Neu")
                },
                containerColor = BridgeAccent,
                contentColor = BridgeBg,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Neu")
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
                    "Profile",
                    color = BridgeAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            items(state.profiles, key = { it.id }) { profile ->
                val active = profile.id == state.activeProfile?.id
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = BridgeBg),
                    headlineContent = { Text(profile.name, color = BridgeText) },
                    supportingContent = {
                        Text(
                            if (active) "aktiv · ${profile.summary()}" else profile.summary(),
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
                                Icon(Icons.Default.Edit, contentDescription = "Bearbeiten", tint = BridgeText)
                            }
                            IconButton(onClick = { onDelete(profile.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Löschen", tint = BridgeText)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onActivate(profile.id) },
                )
            }

            // —— 2. Antennen ——
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Antennen (AZ-Rotor)",
                    color = BridgeAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                if (!state.rotor.connected) {
                    Text(
                        "Verbinden, um Antennenwerte vom Rotor zu lesen und zu speichern.",
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
                    enabled = state.rotor.connected,
                    selected = state.rotor.selectedAntenna == slot,
                    onSave = { onSaveAntenna(slot, it) },
                )
            }

            // —— 3. Kompass-Anzeige ——
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Kompass-Anzeige",
                    color = BridgeAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Text(
                    "Strom- und Standzeit-Ringe am Kompass ein-/ausblenden",
                    color = BridgeMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
                SettingsCheckRow(
                    checked = state.showStromRing,
                    onChange = onShowStromRingChange,
                    title = "Stromverbrauch-Ring",
                    subtitle = "36 Bins am Kompass, blau→rot nach Last",
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
                    title = "Standzeit-Ring",
                    subtitle = "Farbe nach Stillstandszeit je Sektor",
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
                    "Öffnungswinkel-Overlay",
                    color = BridgeAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Text(
                    "Zartes Farboverlay der Antennenöffnung im AZ-Kompass",
                    color = BridgeMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
                SettingsCheckRow(
                    checked = state.showBeamOverlay,
                    onChange = onShowBeamOverlayChange,
                    title = "Öffnungswinkel anzeigen",
                    subtitle = "Overlay je gewählter Antenne",
                )
            }

            // —— Standort (Karte) ——
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Standort (Karte)",
                    color = BridgeAccent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                Text(
                    "QTH für Beam und Kartenklick-Peilung",
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

            // —— Ende Einstellungen ——
        }
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
            label = "Breite (°)",
            keyboardType = KeyboardType.Decimal,
            keyboardActions = KeyboardActions(),
        )
        ProfileField(
            value = lonText,
            onValueChange = {
                lonText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' || ch == '-' }
            },
            label = "Länge (°)",
            keyboardType = KeyboardType.Decimal,
            keyboardActions = KeyboardActions(),
        )
        ProfileField(
            value = locText,
            onValueChange = { locText = it.uppercase().filter { ch -> ch.isLetterOrDigit() } },
            label = "Maidenhead-Locator (optional)",
            keyboardActions = KeyboardActions(),
        )
        BridgeButton(
            text = "STANDORT SPEICHERN",
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
        label = "Standzeit bis Rot (Minuten)",
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
        text = "ZEIT SPEICHERN",
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
        "Heatmap Azimut",
        color = BridgeAccent,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Text(
        "Ohne eigene Skala: Auto mit Mindestspanne (kleine Differenzen bleiben grünlich). Mit Skala: Normbereich grün, darunter blau, darüber rot.",
        color = BridgeMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    )
    SettingsCheckRow(
        checked = prefs.heatmapCustom,
        onChange = onCustomChange,
        title = "Eigene Farb-Skala verwenden",
        subtitle = "blau ≤ Norm min ≤ Norm max ≤ rot",
    )
    if (prefs.heatmapCustom) {
        val done = KeyboardActions(onDone = { focus.clearFocus() })
        ProfileField(
            value = thrBlue,
            onValueChange = { thrBlue = it.filter { ch -> ch.isDigit() } },
            label = "Untere Schwelle (blau)",
            keyboardType = KeyboardType.Number,
            keyboardActions = done,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ProfileField(
            value = normMin,
            onValueChange = { normMin = it.filter { ch -> ch.isDigit() } },
            label = "Normbereich min (grün)",
            keyboardType = KeyboardType.Number,
            keyboardActions = done,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ProfileField(
            value = normMax,
            onValueChange = { normMax = it.filter { ch -> ch.isDigit() } },
            label = "Normbereich max (grün)",
            keyboardType = KeyboardType.Number,
            keyboardActions = done,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        ProfileField(
            value = thrRed,
            onValueChange = { thrRed = it.filter { ch -> ch.isDigit() } },
            label = "Obere Schwelle (rot)",
            keyboardType = KeyboardType.Number,
            keyboardActions = done,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        BridgeButton(
            text = "SKALA SPEICHERN",
            onClick = {
                onSave(true, parse(thrBlue), parse(normMin), parse(normMax), parse(thrRed))
            },
            compact = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
        BridgeButton(
            text = "AUS AKTUELLEN BINS",
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
        label = "Richtungs-Einteilung (Sektoren)",
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
        "10–100 Sektoren (wie Bridge Standzeit-Ring)",
        color = BridgeMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
    BridgeButton(
        text = "SEKTOREN SPEICHERN",
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
                Text("Woher der Wind kommt", color = BridgeText)
                Text("Pfeil zeigt zur Herkunft", color = BridgeMuted, fontSize = 12.sp)
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
                Text("Wohin der Wind weht", color = BridgeText)
                Text("Pfeil zeigt in Windrichtung", color = BridgeMuted, fontSize = 12.sp)
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

    val summary = buildString {
        val n = name.trim().ifEmpty { "ohne Name" }
        append(n)
        append(" · ")
        append(offset.ifEmpty { "0" })
        append("°")
        if (dipole) append(" · Dipol")
        if (selected) append(" · aktiv")
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
                    text = "Antenne $slot",
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
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Zuklappen" else "Aufklappen",
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
                    label = "Name (max. 9)",
                    enabled = enabled,
                    keyboardActions = KeyboardActions(),
                )
                ProfileField(
                    value = offset,
                    onValueChange = { offset = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                    label = "Versatzwinkel °",
                    keyboardType = KeyboardType.Decimal,
                    enabled = enabled,
                    keyboardActions = KeyboardActions(),
                )
                ProfileField(
                    value = opening,
                    onValueChange = { opening = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                    label = "Öffnungswinkel °",
                    keyboardType = KeyboardType.Decimal,
                    enabled = enabled,
                    keyboardActions = KeyboardActions(),
                )
                ProfileField(
                    value = range,
                    onValueChange = { range = it.filter { ch -> ch.isDigit() } },
                    label = "Reichweite km",
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
                    Text("Dipol", color = if (enabled) BridgeText else BridgeMuted)
                }
                BridgeButton(
                    text = "SPEICHERN",
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
                title = { Text("Profil bearbeiten") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    BridgeButton(
                        text = "SPEICHERN",
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
                label = "Name",
                keyboardActions = saveActions,
            )
            ProfileField(
                value = host,
                onValueChange = { host = it },
                label = "IP / Host",
                keyboardActions = saveActions,
            )
            ProfileField(
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                label = "Port",
                keyboardType = KeyboardType.Number,
                keyboardActions = saveActions,
            )
            ProfileField(
                value = masterId,
                onValueChange = { masterId = it.filter { ch -> ch.isDigit() } },
                label = "Master-ID",
                keyboardType = KeyboardType.Number,
                keyboardActions = saveActions,
            )
            ProfileField(
                value = slaveAz,
                onValueChange = { slaveAz = it.filter { ch -> ch.isDigit() } },
                label = "Slave AZ",
                keyboardType = KeyboardType.Number,
                keyboardActions = saveActions,
            )
            ProfileField(
                value = controllerId,
                onValueChange = { controllerId = it.filter { ch -> ch.isDigit() } },
                label = "Panel-Controller-ID (SETPOSCC / GETASELECT)",
                keyboardType = KeyboardType.Number,
                keyboardActions = saveActions,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = enableEl, onCheckedChange = { enableEl = it })
                Text("Elevation vorhanden")
            }
            if (enableEl) {
                ProfileField(
                    value = slaveEl,
                    onValueChange = { slaveEl = it.filter { ch -> ch.isDigit() } },
                    label = "Slave EL",
                    keyboardType = KeyboardType.Number,
                    keyboardActions = saveActions,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = enableWind, onCheckedChange = { enableWind = it })
                Column {
                    Text("Wind aktiv (Anemometer)", color = BridgeText)
                    Text(
                        "SETWINDENABLE am AZ · Abfrage nur wenn aktiv",
                        color = BridgeMuted,
                        fontSize = 12.sp,
                    )
                }
            }
            if (enableWind) {
                Text(
                    "Windpfeil zeigt",
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
