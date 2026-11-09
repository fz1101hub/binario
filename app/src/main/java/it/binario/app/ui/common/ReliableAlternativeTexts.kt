package it.binario.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.binario.app.R
import it.binario.app.data.reliability.StopReliability

/**
 * The texts of «Alternativa più puntuale» under a direct train, as functions of their values: resolved from the app
 * resources on screen ([rememberReliableAlternativeStrings]) and from the resource files in the JVM tests.
 */
class ReliableAlternativeStrings(
    /** «Più puntuale: Regionale 2157 delle 08:12» (the train as its card names it, and its departure). */
    val train: (label: String, time: String) -> String,
    /** «in orario 9 volte su 10» after the train (plural on the first number). */
    val onTime: (onTime: Int, total: Int) -> String,
    /** «Mostra il treno delle 08:12»: what a tap does, announced by TalkBack. */
    val open: (time: String) -> String,
    /** The sentence TalkBack reads, which says the statistics are unofficial ([NetworkReliabilityStrings.spoken]). */
    val spoken: (text: String) -> String
)

/** The line shown under a train's reliability line, the sentence TalkBack reads for it and its click label. */
@Immutable
data class ReliableAlternativeText(val line: NetworkReliabilityLine, val spoken: String, val actionLabel: String)

/** The line of the train suggested instead of another, in the style of the other statistics lines. */
object ReliableAlternativeLines {
    /** [label] and [time] name the suggested train as its card does; [stop] is its reliability at the destination. */
    fun text(label: String, time: String, stop: StopReliability, strings: ReliableAlternativeStrings): ReliableAlternativeText {
        val line = NetworkReliabilityLine(listOf(strings.train(label, time), strings.onTime(stop.onTime, stop.samples)))
        return ReliableAlternativeText(line, strings.spoken(line.spokenText), strings.open(time))
    }
}

@Composable
internal fun rememberReliableAlternativeStrings(): ReliableAlternativeStrings {
    val resources = LocalResources.current
    return remember(resources) {
        ReliableAlternativeStrings(
            train = { label, time -> resources.getString(R.string.reliability_alternative_train, label, time) },
            onTime = { count, total -> resources.getQuantityString(R.plurals.reliability_alternative_on_time, count, count, total) },
            open = { time -> resources.getString(R.string.reliability_alternative_open, time) },
            spoken = { text -> resources.getString(R.string.reliability_spoken, text) }
        )
    }
}

/**
 * «Più puntuale: Regionale 2157 delle 08:12 · in orario 9 volte su 10» under the reliability line of a result card: a
 * button of its own (at least 48 dp high), read as one sentence with its click label, that shows the suggested train.
 */
@Composable
internal fun ReliableAlternativeLine(text: ReliableAlternativeText, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = text.actionLabel, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = text.spoken
                role = Role.Button
                onClick(label = text.actionLabel) { onClick(); true }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.SwapVert, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text.line.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Medium)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    }
}
