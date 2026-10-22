package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Actual hit regions never scale; only the decorative active pill has a jelly pulse. */
@Composable
fun FloatingNavigation(selected: Int, reduceMotion: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val labels = listOf("首页", "关注", "我的")
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.FavoriteBorder, Icons.Outlined.Person)
    val density = LocalDensity.current
    val height = (62 * density.fontScale.coerceAtLeast(1f)).coerceAtMost(116f).dp
    val pulse = remember { Animatable(1f) }
    var previousSelection by remember { mutableIntStateOf(selected) }
    LaunchedEffect(selected, reduceMotion) {
        val changed = selected != previousSelection
        previousSelection = selected
        pulse.snapTo(1f)
        if (changed && !reduceMotion) {
            pulse.animateTo(1.09f, tween(90))
            pulse.animateTo(0.97f, tween(70))
            pulse.animateTo(1f, tween(80))
        }
    }
    Surface(modifier.widthIn(max = if (density.fontScale > 1.3f) 320.dp else 268.dp).fillMaxWidth().height(height), shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Row(Modifier.padding(5.dp).selectableGroup()) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(25.dp))
                    .selectable(active, role = Role.Tab, onClick = { onSelect(index) }), contentAlignment = Alignment.Center) {
                    if (active) Box(Modifier.fillMaxSize().padding(2.dp).graphicsLayer {
                        scaleX = pulse.value; scaleY = 2f - pulse.value
                    }.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(24.dp)))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icons[index], contentDescription = null, modifier = Modifier.size(22.dp),
                            tint = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
