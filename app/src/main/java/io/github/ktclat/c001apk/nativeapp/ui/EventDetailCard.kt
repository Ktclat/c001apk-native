// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.feature.media.MediaGrid
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


@Composable
internal fun EventDetailCard(item: ContentItem, openLink: (String) -> Unit) {
    val event = item.event ?: return
    Column(Modifier.fillMaxWidth().padding(6.dp).testTag("event-detail:${item.id}"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (item.imageUrls.isNotEmpty()) MediaGrid(item.imageUrls)
        Text(item.title, style = MaterialTheme.typography.headlineSmall)
        event.deadlineSeconds?.let { seconds ->
            val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
            Text("报名截止：$time", style = MaterialTheme.typography.bodyMedium)
        }
        event.sections.forEach { section -> key(section.label, section.body) {
            Column(Modifier.testTag("event-section:${section.label}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(section.label, style = MaterialTheme.typography.titleMedium)
                FullPostText(section.body, "", openLink, null, parser = if (section.literal) ::prepareEventLiteral else ::prepareEventHtml)
            }
        } }
        if (event.sections.isEmpty()) Text("接口未提供活动正文", color = MaterialTheme.colorScheme.onSurfaceVariant)
        event.actionUrl?.let { url -> Button(onClick = { openLink(url) }, modifier = Modifier.testTag("event-registration")) { Text("查看报名页面") } }
    }
}
