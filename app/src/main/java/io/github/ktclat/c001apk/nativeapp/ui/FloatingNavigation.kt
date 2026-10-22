package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke

internal val DecorativeJellyPosition = SemanticsPropertyKey<Float>("DecorativeJellyPosition")
internal val DecorativeJellyScale = SemanticsPropertyKey<Float>("DecorativeJellyScale")

/** Actual hit regions never scale; only the decorative active pill has a jelly pulse. */
@Composable
fun FloatingNavigation(selected: Int, reduceMotion: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val labels = listOf("首页", "关注", "我的")
    val icons = (0..2).map { rootNavigationIcon(it, it == selected) }
    val density = LocalDensity.current
    val height = floatingNavigationHeight()
    val pulse = remember { Animatable(1f) }
    val position = remember { Animatable(selected.toFloat()) }
    val direction = LocalLayoutDirection.current
    var previousSelection by remember { mutableIntStateOf(selected) }
    LaunchedEffect(selected, reduceMotion) {
        val changed = selected != previousSelection
        previousSelection = selected
        if (!changed || reduceMotion) {
            position.snapTo(selected.toFloat())
            pulse.snapTo(1f)
        } else coroutineScope {
            // Continue from the current decorative location when a rapid tap interrupts travel.
            // This never moves or scales the actual destinations, icons, labels or hit regions.
            val start = position.value
            val target = selected.toFloat()
            launch {
                position.animateTo(target, keyframes {
                    durationMillis = 360
                    start at 0 using FastOutSlowInEasing
                    (target + (target - start) * .035f) at 250 using FastOutSlowInEasing
                    target at 360
                })
            }
            launch {
                pulse.animateTo(1.12f, tween(110))
                pulse.animateTo(.96f, tween(90))
                pulse.animateTo(1f, tween(160))
            }
        }
    }
    Surface(modifier.testTag("floating-navigation").widthIn(max = if (density.fontScale >= 1.3f) 320.dp else 268.dp).fillMaxWidth().height(height), shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .34f)), shadowElevation = 4.dp) {
        BoxWithConstraints(Modifier.padding(5.dp)) {
            val destinationWidth = maxWidth / labels.size
            val destinationPixels = with(density) { destinationWidth.toPx() }
            Box(Modifier.width(destinationWidth).fillMaxHeight().testTag("selected-pill")
                .semantics {
                    this[DecorativeJellyScale] = pulse.value
                    this[DecorativeJellyPosition] = position.value
                }.graphicsLayer {
                    translationX = position.value * destinationPixels * if (direction == LayoutDirection.Ltr) 1f else -1f
                    scaleX = pulse.value
                    scaleY = 2f - pulse.value
                }.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50)))
            Row(Modifier.fillMaxSize().selectableGroup()) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                    .selectable(active, role = Role.Tab, onClick = { onSelect(index) }), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icons[index], contentDescription = null, modifier = Modifier.size(22.dp),
                            tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text(label, fontSize = 12.5.sp, lineHeight = 14.375.sp, fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            }
        }
    }
}

/** Grow only the label line, not icon or decorative padding, at large accessibility scales. */
@Composable internal fun floatingNavigationHeight(): androidx.compose.ui.unit.Dp = with(LocalDensity.current) {
    (22.dp + 2.dp + 14.375.sp.toDp() + 14.dp).coerceAtLeast(if (fontScale >= 1.8f) 72.dp else if (fontScale >= 1.3f) 66.dp else 62.dp)
}
