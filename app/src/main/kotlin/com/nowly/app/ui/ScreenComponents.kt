package com.nowly.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nowly.app.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ScreenBar(title: String, onBack: () -> Unit, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("screen-back")) {
            Icon(painterResource(R.drawable.ic_chevron_back), "返回", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, Modifier.weight(1f).testTag("screen-title"), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        action?.invoke(this)
    }
}

fun minuteLabel(value: Int): String = "%02d:%02d".format(value / 60, value % 60)
fun timeLabel(at: Long, zone: String): String = Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("HH:mm"))
