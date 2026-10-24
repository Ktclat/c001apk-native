package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Flutter-style label padding/content-width indicator, without Material's unrelated90dp tab minimum. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun HomeCategoryTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val requests = remember(labels.size) { List(labels.size) { BringIntoViewRequester() } }
    val placed = remember(labels.size) { List(labels.size) { mutableStateOf(false) } }
    LaunchedEffect(selected, requests, placed.getOrNull(selected)?.value) {
        if (placed.getOrNull(selected)?.value == true) requests.getOrNull(selected)?.bringIntoView()
    }
    Row(modifier.horizontalScroll(rememberScrollState()).selectableGroup()) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Box(Modifier.onGloballyPositioned { placed[index].value = true }.bringIntoViewRequester(requests[index]).widthIn(min = 48.dp).heightIn(min = 48.dp)
                .testTag("home-category:$index").selectable(active, role = Role.Tab, onClick = { onSelect(index) })
                .padding(horizontal = 16.dp), contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, Modifier.padding(top = 12.dp, bottom = 10.dp), fontSize = 14.sp, lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.fillMaxWidth().height(3.dp).testTag("home-category-indicator:$index")
                        .background(if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}
