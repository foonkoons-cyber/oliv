package com.khabar.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khabar.reader.data.Prefs
import com.khabar.reader.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: Prefs,
    feedCount: Int,
    articleCount: Int,
    onThemeMode: (ThemeMode) -> Unit,
    onTextScale: (Float) -> Unit,
    onRefreshIntervalHours: (Int) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onKeepPerFeed: (Int) -> Unit,
    onMarkReadOnOpen: (Boolean) -> Unit,
    onShowImages: (Boolean) -> Unit,
    onClearUnsaved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Wapas")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
        ) {
            Section("Reading")

            SettingRow("Theme") {
                ChipChoices(
                    options = ThemeMode.entries.map { it to themeLabel(it) },
                    selected = prefs.themeMode,
                    onSelect = onThemeMode
                )
            }

            SettingRow("Text size") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = prefs.textScale,
                        onValueChange = onTextScale,
                        valueRange = 0.85f..1.6f,
                        steps = 4
                    )
                    // A live sample beats a percentage: you are choosing how reading feels.
                    Text(
                        text = "Aise dikhega — ek line asli article jaisi.",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 17.sp * prefs.textScale,
                            lineHeight = 27.sp * prefs.textScale
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            ToggleRow(
                title = "Images dikhao",
                subtitle = "Timeline aur article mein thumbnails",
                checked = prefs.showImages,
                onChange = onShowImages
            )

            ToggleRow(
                title = "Kholte hi read mark karo",
                subtitle = "Band karo to read/unread khud manage karo",
                checked = prefs.markReadOnOpen,
                onChange = onMarkReadOnOpen
            )

            Section("Refresh")

            SettingRow("Background refresh") {
                ChipChoices(
                    options = listOf(0, 1, 3, 6, 12, 24).map { it to intervalLabel(it) },
                    selected = prefs.refreshIntervalHours,
                    onSelect = onRefreshIntervalHours
                )
            }

            ToggleRow(
                title = "Sirf Wi-Fi par",
                subtitle = "Mobile data par background refresh nahi hoga",
                checked = prefs.wifiOnly,
                onChange = onWifiOnly
            )

            Section("Storage")

            SettingRow("Har feed ke kitne articles rakhein") {
                ChipChoices(
                    options = listOf(50, 100, 200, 500, 1000).map { it to it.toString() },
                    selected = prefs.keepPerFeed,
                    onSelect = onKeepPerFeed
                )
            }

            Text(
                text = "$feedCount feeds · $articleCount articles is list mein",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { confirmClear = true }
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "Articles clear karo",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Section("Khabar ke baare mein")

            Text(
                text = "Khabar ka koi server nahi hai aur koi account nahi chahiye. Phone " +
                    "seedha publisher ke public feed URL se padhta hai, aur jo aata hai wo " +
                    "isi phone mein rehta hai — offline bhi. App hataoge to sab chala jayega.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(Modifier.height(40.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Articles clear karein?") },
            text = {
                Text(
                    "Saved articles nahi hatenge — sirf baaki sab. Agli refresh par feeds " +
                        "dobara bhar jayenge."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearUnsaved()
                    confirmClear = false
                }) { Text("Clear karo") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Rehne do") }
            }
        )
    }
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> "System"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}

private fun intervalLabel(hours: Int): String = when (hours) {
    0 -> "Off"
    1 -> "1 ghanta"
    else -> "$hours ghante"
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingRow(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** One shape for every "pick one of a few" setting, so they all behave the same. */
@Composable
private fun <T> ChipChoices(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, (value, label) ->
            if (index > 0) Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) }
            )
        }
    }
}
