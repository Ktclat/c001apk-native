// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** No image loader or network dependency: every button inserts its complete literal text token. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextEmoticonPicker(enabled: Boolean, onInsert: (String) -> Unit, onClose: () -> Unit) {
    val effectiveDensity = LocalDensity.current
    var groupIndex by rememberSaveable { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Dialog owns another Android window: explicitly preserve the live system × app font scale.
        CompositionLocalProvider(LocalDensity provides effectiveDensity) {
            // A separate Dialog may be edge-to-edge even when its declared default fits system
            // windows. Respect the current window's per-edge maximum, not a device or pixel guess.
            Surface(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.systemGestures))
                .padding(horizontal = 16.dp, vertical = 16.dp).widthIn(max = 560.dp)
                .fillMaxWidth().testTag("composer-emoticon-dialog"),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp)) {
                    // Close is outside both scroll areas and stays reachable in short/large-text windows.
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        Text("表情文字", Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("composer-emoticon-close")) {
                            Text("关闭")
                        }
                    }
                    // The introduction, groups and tokens share bounded scrolling, so no fixed header can
                    // consume the remaining viewport at 200% font scale in landscape.
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .testTag("composer-emoticon-scroll"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("插入文字标记，不含表情图片；不会发送。", style = MaterialTheme.typography.bodyMedium)
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextEmoticons.groups.forEachIndexed { index, group ->
                                FilterChip(selected = groupIndex == index, onClick = { groupIndex = index },
                                    label = { Text(group.label) }, modifier = Modifier.heightIn(min = 48.dp)
                                        .testTag("composer-emoticon-group-$index"))
                            }
                        }
                        key(groupIndex) {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextEmoticons.groups[groupIndex].tokens.forEach { token ->
                                    OutlinedButton(enabled = enabled, onClick = { onInsert(token) },
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("composer-emoticon-$token")
                                            .semantics { contentDescription = "插入$token" },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                                        Text(token, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
