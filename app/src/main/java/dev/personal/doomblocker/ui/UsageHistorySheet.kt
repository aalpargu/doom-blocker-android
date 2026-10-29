package dev.personal.doomblocker.ui

import android.graphics.Canvas as AndroidCanvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import dev.personal.doomblocker.core.ClockReading
import dev.personal.doomblocker.core.DailyAppRecord
import dev.personal.doomblocker.core.ProtectionSnapshot
import dev.personal.doomblocker.platform.InstalledApp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val historyLocale = Locale.forLanguageTag("tr")
private val chartBarShape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)

/** The sheet owns presentation state only. Usage and limit records remain in the existing snapshot. */
@Composable
internal fun UsageHistorySheet(
    snapshot: ProtectionSnapshot,
    now: ClockReading,
    installedByPackage: Map<String, InstalledApp>,
    onDismiss: () -> Unit,
) {
    var rangeDays by rememberSaveable { mutableIntStateOf(7) }
    var dailyExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var sortKey by rememberSaveable { mutableIntStateOf(0) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val dashboard = remember(snapshot, rangeDays, now.wallMillis / 10_000L) {
        buildHistoryDashboard(snapshot, now, rangeDays)
    }
    val selectedApp = dashboard.apps.firstOrNull { it.packageName == selectedPackage }
    val listState = rememberLazyListState()
    BackHandler(enabled = selectedApp != null) { selectedPackage = null }
    LaunchedEffect(selectedPackage) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { if (selectedApp != null) selectedPackage = null else onDismiss() },
                modifier = Modifier.semantics {
                    contentDescription = if (selectedApp != null) "Kullanım geçmişine dön" else "Kapat"
                },
            ) {
                Text("←", style = MaterialTheme.typography.headlineSmall)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    selectedApp?.label ?: "Kullanım geçmişi",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selectedApp != null) {
                    Text("Uygulama ayrıntısı", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp, end = 18.dp, top = 16.dp, bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (selectedApp != null) {
                item(key = "app_hero") {
                    HistoryContentWidth {
                        HistoryAppDetailHero(selectedApp, dashboard, installedByPackage[selectedApp.packageName])
                    }
                }
                item(key = "app_range") {
                    HistoryContentWidth { HistoryRangePicker(rangeDays) { rangeDays = it } }
                }
                item(key = "app_chart") {
                    HistoryContentWidth {
                        HistoryChartCard(
                            title = "Günlük kullanım",
                            days = selectedApp.dailyUsage,
                            activeEpochDay = snapshot.activeEpochDay,
                        )
                    }
                }
            } else {
                dashboard.insight?.let { insight ->
                    item(key = "insight") {
                        HistoryContentWidth {
                            HistoryInsightCard(insight) {
                                selectedPackage = insight.packageName
                            }
                        }
                    }
                }
                item(key = "range") {
                    HistoryContentWidth { HistoryRangePicker(rangeDays) { rangeDays = it } }
                }
                item(key = "chart") {
                    HistoryContentWidth {
                        HistoryChartCard(
                            title = "Son $rangeDays gün",
                            days = dashboard.days,
                            activeEpochDay = snapshot.activeEpochDay,
                            headline = formatHistoryDuration(dashboard.totalMillis),
                            comparison = dashboard.comparison,
                        )
                    }
                }
                item(key = "metrics") {
                    HistoryContentWidth { HistoryMetricGrid(dashboard) }
                }
                item(key = "daily_heading") {
                    HistoryContentWidth {
                        Column {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .clickable(role = Role.Button) { dailyExpanded = !dailyExpanded }
                                    .semantics { stateDescription = if (dailyExpanded) "Açık" else "Kapalı" },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Günlük ayrıntılar", Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleLarge)
                                Text(if (dailyExpanded) "Gizle ↑" else "Göster ↓",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge)
                            }
                            if (dailyExpanded) {
                                Text("Çubuklar seçili dönemin en yüksek gününe göre.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (dailyExpanded) {
                    val maxDay = dashboard.days.maxOfOrNull(HistoryDay::usedMillis)?.coerceAtLeast(1L) ?: 1L
                    items(dashboard.days.asReversed(), key = { "day_${it.epochDay}" }) { day ->
                        HistoryContentWidth {
                            HistoryDayRow(
                                day = day,
                                maxUsage = maxDay,
                                expanded = expandedDay == day.epochDay,
                                onClick = { expandedDay = if (expandedDay == day.epochDay) null else day.epochDay },
                            )
                        }
                    }
                }
                item(key = "apps_heading") {
                    HistoryContentWidth {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Uygulamalar", Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleLarge)
                                Box {
                                    TextButton(onClick = { sortMenuExpanded = true }) {
                                        Text(when (sortKey) {
                                            1 -> "En çok limit ▾"
                                            2 -> "En uzun oturum ▾"
                                            else -> "En çok kullanılan ▾"
                                        })
                                    }
                                    DropdownMenu(expanded = sortMenuExpanded,
                                        onDismissRequest = { sortMenuExpanded = false }) {
                                        listOf("En çok kullanılan", "En çok limit", "En uzun oturum")
                                            .forEachIndexed { index, title ->
                                                DropdownMenuItem(text = { Text(title) }, onClick = {
                                                    sortKey = index
                                                    sortMenuExpanded = false
                                                })
                                            }
                                    }
                                }
                            }
                            Text("Çubuklar en çok kullanılan uygulamaya göre.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val sortedApps = when (sortKey) {
                    1 -> dashboard.apps.sortedWith(compareByDescending<HistoryApp> { it.limitEvents }
                        .thenByDescending { it.usedMillis })
                    2 -> dashboard.apps.sortedWith(compareByDescending<HistoryApp> { it.longestSessionMillis }
                        .thenByDescending { it.usedMillis })
                    else -> dashboard.apps
                }
                if (sortedApps.isEmpty()) {
                    item(key = "apps_empty") {
                        HistoryContentWidth {
                            Text("Bu dönemde uygulama kullanım kaydı yok.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    val maxApp = dashboard.apps.maxOfOrNull(HistoryApp::usedMillis)?.coerceAtLeast(1L) ?: 1L
                    items(sortedApps, key = { "app_${it.packageName}" }) { app ->
                        HistoryContentWidth {
                            HistoryAppRow(
                                app = app,
                                installed = installedByPackage[app.packageName],
                                maxUsage = maxApp,
                                onClick = { selectedPackage = app.packageName },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryContentWidth(content: @Composable () -> Unit) {
    Box(Modifier.widthIn(max = DoomTokens.maxContentWidth).fillMaxWidth()) { content() }
}

@Composable
private fun HistoryInsightCard(insight: HistoryInsight, onOpenApp: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(34.dp), shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Text("↗", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column(Modifier.weight(1f).padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(insight.title, style = MaterialTheme.typography.titleMedium)
                Text(insight.body, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (insight.packageName != null) {
                TextButton(onClick = onOpenApp) { Text("İncele") }
            }
        }
    }
}

@Composable
private fun HistoryRangePicker(selected: Int, onSelect: (Int) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(7, 30).forEach { days ->
                val active = selected == days
                Surface(
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                        .clickable(role = Role.Tab) { onSelect(days) }
                        .semantics { stateDescription = if (active) "Seçili" else "Seçili değil" },
                    shape = RoundedCornerShape(11.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("$days gün", style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryChartCard(
    title: String,
    days: List<HistoryDay>,
    activeEpochDay: Long,
    headline: String? = null,
    comparison: String? = null,
) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (headline != null) Text(headline,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold)
                val knownDays = days.count(HistoryDay::recorded)
                if (knownDays < days.size) {
                    Text("$knownDays / ${days.size} gün kayıtlı",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (comparison != null) {
                    Text(comparison, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (days.any(HistoryDay::recorded)) {
                HistoryBarChart(days, activeEpochDay)
            } else {
                Text("Henüz kullanım görünmüyor. Birkaç gün sonra eğilimini burada görebilirsin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HistoryBarChart(days: List<HistoryDay>, activeEpochDay: Long) {
    if (days.isEmpty()) return
    var selectedEpochDay by rememberSaveable(days.first().epochDay, days.last().epochDay) {
        mutableLongStateOf(days.last().epochDay)
    }
    val selectedDay = days.firstOrNull { it.epochDay == selectedEpochDay } ?: days.last()
    val maxUsage = days.maxOf(HistoryDay::usedMillis).coerceAtLeast(1L)
    val scrollState = rememberScrollState()
    LaunchedEffect(days.size, scrollState.maxValue) {
        if (scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("En yüksek gün ${formatHistoryDuration(maxUsage)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minimumBarWidth = if (days.size <= 7) 40.dp else 48.dp
            val needsScroll = maxWidth < minimumBarWidth * days.size
            Row(
                modifier = if (needsScroll) Modifier.horizontalScroll(scrollState) else Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEach { day ->
                    val selected = day.epochDay == selectedDay.epochDay
                    val barColor = when {
                        !day.recorded -> MaterialTheme.colorScheme.outlineVariant
                        selected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.primary.copy(alpha = .6f)
                    }
                    Column(
                        modifier = (if (needsScroll) Modifier.width(48.dp) else Modifier.weight(1f))
                            .clickable(role = Role.Button) { selectedEpochDay = day.epochDay }
                            .semantics {
                                contentDescription = buildString {
                                    append(historyFullDay(day.epochDay)).append(", ")
                                    append(if (day.recorded) formatHistoryDuration(day.usedMillis) else "kayıt yok")
                                    append(", ").append(day.limitEvents).append(" kez limite ulaşıldı")
                                    if (day.epochDay == activeEpochDay) append(", bugün devam ediyor")
                                }
                                stateDescription = if (selected) "Seçili" else "Seçili değil"
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.fillMaxWidth().height(126.dp), contentAlignment = Alignment.BottomCenter) {
                            val height = if (day.usedMillis <= 0L) 3.dp else
                                (112.dp * (day.usedMillis.toFloat() / maxUsage).coerceIn(0f, 1f))
                                    .coerceAtLeast(5.dp)
                            Box(Modifier.width(22.dp).height(height).clip(chartBarShape).background(barColor))
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            if (days.size <= 7) historyShortDay(day.epochDay)
                            else LocalDate.ofEpochDay(day.epochDay).dayOfMonth.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(if (day.epochDay == activeEpochDay) "•" else " ",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(historyFullDay(selectedDay.epochDay) +
                    if (selectedDay.epochDay == activeEpochDay) " · bugün sürüyor" else "",
                    style = MaterialTheme.typography.labelLarge)
                Text(if (selectedDay.recorded) formatHistoryDuration(selectedDay.usedMillis)
                    else "Kayıt yok", style = MaterialTheme.typography.titleMedium)
                Text("${selectedDay.limitEvents} kez limite ulaşıldı",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HistoryMetricGrid(dashboard: HistoryDashboard) {
    val averageLabel = if (dashboard.days.count(HistoryDay::recorded) == dashboard.rangeDays)
        "Günlük ortalama" else "Kayıtlı gün ort."
    val values = listOf(
        "Toplam kullanım" to formatHistoryDuration(dashboard.totalMillis),
        averageLabel to formatHistoryDuration(dashboard.averageMillis),
        "Limite ulaşma" to dashboard.limitEvents.toString(),
        "En uzun oturum" to formatHistoryDuration(dashboard.longestSessionMillis),
    )
    HistoryMetricGroup(values)
}

@Composable
private fun HistoryMetricGroup(values: List<Pair<String, String>>) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp || fontScale >= 1.5f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                values.forEach { (label, value) ->
                    HistoryMetric(label, value, Modifier.fillMaxWidth())
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                values.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, value) ->
                            HistoryMetric(label, value, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.heightIn(min = 84.dp), shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HistoryDayRow(day: HistoryDay, maxUsage: Long, expanded: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (expanded) "Açık" else "Kapalı" }
            .heightIn(min = 54.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(historyShortDate(day.epochDay), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                Text(if (day.recorded) formatHistoryDuration(day.usedMillis) else "Kayıt yok",
                    style = MaterialTheme.typography.labelLarge)
            }
            HistoryProgress(day.usedMillis.toFloat() / maxUsage, Modifier.fillMaxWidth(),
                MaterialTheme.colorScheme.primary)
            Text("${day.limitEvents} kez limite ulaşıldı" + if (expanded) " · ayrıntıları gizle" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            val appRecords = day.apps.filter { it.usedMillis > 0 || it.limitEvents > 0 }
                .sortedByDescending(DailyAppRecord::usedMillis)
            if (appRecords.isEmpty()) {
                Text(if (day.recorded) "Bu gün kullanım yok." else "Bu güne ait kayıt yok.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                appRecords.forEach { app ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp),
                        verticalAlignment = Alignment.Top) {
                        Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(formatHistoryDuration(app.usedMillis),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
    }
}

@Composable
private fun HistoryAppRow(
    app: HistoryApp,
    installed: InstalledApp?,
    maxUsage: Long,
    onClick: () -> Unit,
) {
    val recordedDays = app.dailyUsage.count(HistoryDay::recorded)
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HistoryAppIcon(installed, app.label)
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Günlük ort. ${formatHistoryDuration(app.usedMillis / recordedDays.coerceAtLeast(1))}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(formatHistoryDuration(app.usedMillis),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold)
        }
        HistoryProgress(app.usedMillis.toFloat() / maxUsage, Modifier.fillMaxWidth(),
            MaterialTheme.colorScheme.primary)
        Text(buildString {
            append(app.limitEvents).append(" kez limite ulaşıldı")
            if (app.longestSessionMillis > 0) {
                append(" · En uzun oturum ").append(formatHistoryDuration(app.longestSessionMillis))
            }
        }, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
    }
}

@Composable
private fun HistoryAppDetailHero(app: HistoryApp, dashboard: HistoryDashboard, installed: InstalledApp?) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HistoryAppIcon(installed, app.label)
                    Column {
                        Text(app.label, style = MaterialTheme.typography.titleLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Son ${dashboard.rangeDays} gün", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(formatHistoryDuration(app.usedMillis),
                    style = MaterialTheme.typography.headlineMedium)
                Text("Toplam kullanım", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val recordedDays = app.dailyUsage.count(HistoryDay::recorded)
        HistoryMetricGroup(listOf(
            "Günlük ortalama" to formatHistoryDuration(app.usedMillis / recordedDays.coerceAtLeast(1)),
            "Limite ulaşılan gün" to "${app.daysWithLimit} / $recordedDays",
            "Limite ulaşma" to app.limitEvents.toString(),
            "En uzun oturum" to formatHistoryDuration(app.longestSessionMillis),
        ))
    }
}

@Composable
private fun HistoryProgress(fraction: Float, modifier: Modifier, color: Color) {
    Box(modifier.height(5.dp).clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                .clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun HistoryAppIcon(installed: InstalledApp?, label: String) {
    if (installed != null) {
        val bitmap = remember(installed.packageName) {
            createBitmap(88, 88).also { bitmap ->
                installed.icon.setBounds(0, 0, 88, 88)
                installed.icon.draw(AndroidCanvas(bitmap))
            }.asImageBitmap()
        }
        Image(bitmap, contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
    } else {
        Surface(Modifier.size(40.dp), shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(contentAlignment = Alignment.Center) {
                Text(label.take(1).uppercase(historyLocale), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun historyShortDay(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("EEE", historyLocale))

private fun historyShortDate(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("EEE d MMM", historyLocale))

private fun historyFullDay(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("d MMMM EEEE", historyLocale))
