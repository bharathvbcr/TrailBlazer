package com.example.trailblazer.ui.sky

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.trailblazer.ui.Fmt
import com.example.trailblazer.ui.components.TrailIcons
import com.trailblazer.core.astro.LunarPhase
import com.trailblazer.core.astro.LunarPhaseInfo
import com.trailblazer.core.astro.MoonPhaseName
import com.trailblazer.core.astro.PrincipalPhase
import com.trailblazer.core.astro.illuminationFor
import com.trailblazer.core.astro.typicalElongation

/**
 * "Moon phases" under the Moon card: a row that opens to the whole cycle as pictures, a short note on the phase you
 * tap (tonight's by default), and the dates of the next four principal phases.
 */
@Composable
fun MoonPhasesDropdown(phase: LunarPhaseInfo, upcoming: List<PrincipalPhase>, latDeg: Double, fmt: Fmt) {
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "moonPhasesChevron")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = if (open) "Hide moon phases" else "Show moon phases") { open = !open }
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Text("Moon phases", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(TrailIcons.Chevron, null, Modifier.size(18.dp).rotate(turn), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        MoonPhasesPanel(phase, upcoming, latDeg, fmt)
    }
}

@Composable
private fun MoonPhasesPanel(phase: LunarPhaseInfo, upcoming: List<PrincipalPhase>, latDeg: Double, fmt: Fmt) {
    var picked by rememberSaveable { mutableStateOf(phase.name) }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        // The cycle: eight pictures, tonight's ringed, with a marker for exactly where in the month tonight is.
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            for (n in MoonPhaseName.entries) {
                val isTonight = n == phase.name
                val isPicked = n == picked
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .then(if (isPicked) Modifier.border(2.dp, cs.primary, CircleShape) else if (isTonight) Modifier.border(1.dp, cs.outline, CircleShape) else Modifier)
                        .clickable(role = Role.Tab) { picked = n }
                        .semantics {
                            contentDescription = phaseName(n) + if (isTonight) ", tonight" else ""
                            selected = isPicked
                        },
                ) {
                    val elong = LunarPhase.typicalElongation(n)
                    MoonDisc(LunarPhase.illuminationFor(n), LunarPhase.litOnRight(elong < 180.0, latDeg), Modifier.size(26.dp), describe = false)
                }
            }
        }
        CycleBar(phase.elongationDeg)
        Spacer(Modifier.height(8.dp))
        Text(phaseName(picked), style = MaterialTheme.typography.titleMedium)
        Text(
            aboutPhase(picked),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        if (upcoming.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Coming up", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            for (p in upcoming) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    MoonDisc(LunarPhase.illuminationFor(p.name), LunarPhase.litOnRight(p.targetDeg < 180.0, latDeg), Modifier.size(20.dp), describe = false)
                    Spacer(Modifier.width(12.dp))
                    Text(phaseName(p.name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${fmt.date(p.epochMs)} ${fmt.time(p.epochMs)}", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
                }
            }
        }
    }
}

/** A thin bar across the pictures: new moon at both ends, full in the middle, and a dot for tonight. */
@Composable
private fun CycleBar(elongationDeg: Double) {
    val cs = MaterialTheme.colorScheme
    val track = cs.onSurface.copy(alpha = 0.12f)
    val dot = cs.primary
    val f = (elongationDeg / 360.0).toFloat().coerceIn(0f, 1f)
    Canvas(
        Modifier.fillMaxWidth().height(14.dp).padding(horizontal = 19.dp).semantics {
            contentDescription = "Day ${(elongationDeg / 360.0 * LunarPhase.SYNODIC_MONTH_DAYS).toInt() + 1} of the ${LunarPhase.SYNODIC_MONTH_DAYS.toInt()}-and-a-half-day cycle"
        },
    ) {
        val y = size.height / 2
        drawLine(track, Offset(0f, y), Offset(size.width, y), 3.dp.toPx())
        drawCircle(dot, 4.dp.toPx(), Offset(size.width * f, y))
    }
}

/** What the phase looks like and when it is up. General facts, true at any latitude away from the poles. */
internal fun aboutPhase(n: MoonPhaseName): String = when (n) {
    MoonPhaseName.NewMoon -> "Between Earth and the Sun, so its lit side faces away: not visible. It rises and sets with the Sun. The darkest nights for stargazing."
    MoonPhaseName.WaxingCrescent -> "A thin sliver low in the west after sunset, growing each night. The dark part often glows faintly with earthshine."
    MoonPhaseName.FirstQuarter -> "Half lit. Rises around midday, is highest at sunset and sets around midnight. Craters stand out along the shadow line."
    MoonPhaseName.WaxingGibbous -> "More than half lit and growing. Up through the evening, setting in the small hours."
    MoonPhaseName.FullMoon -> "Opposite the Sun: rises at sunset, is highest around midnight and sets at sunrise. Bright enough to wash out faint stars."
    MoonPhaseName.WaningGibbous -> "More than half lit and shrinking. Rises after sunset and is still up in the morning."
    MoonPhaseName.LastQuarter -> "Half lit, the other half. Rises around midnight, is highest at sunrise and sets around midday."
    MoonPhaseName.WaningCrescent -> "A thin sliver low in the east before dawn, shrinking each morning, then lost in the Sun's glare."
}
