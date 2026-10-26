package io.embodit.lightplanner.ui.planner.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.lightplanner.BuildConfig
import io.embodit.lightplanner.R
import io.embodit.lightplanner.domain.LanguagePref
import io.embodit.lightplanner.ui.planner.PlannerEvent
import io.embodit.lightplanner.ui.planner.PlannerUiState
import io.embodit.lightplanner.ui.planner.debug.probe
import io.embodit.lightplanner.ui.theme.LpType

private val RowH = 44.dp

/**
 * The settings slot (design `panels.settings`, 400 x 300 dp, right of the main panel): language,
 * spacing lines, clear plan (its confirmation replaces the content, S10), about. A pre-built attached
 * panel, not a native popup (design R1-5).
 */
@Composable
internal fun SettingsPanel(state: PlannerUiState?, onEvent: (PlannerEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.SETTINGS_W.dp, PanelSizes.SETTINGS_H.dp)
                .clip(RoundedCornerShape(28.dp))
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state == null) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        val grey = PicoTheme.colorScheme.labelSecondary
        if (state.clearConfirm) {
            Spacer(modifier = Modifier.weight(1f))
            Text(text = rs(R.string.clear_confirm, state.lamps.size), color = ink, style = LpType.instruction, maxLines = 2, modifier = Modifier.probe("clear_confirm"))
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) { Secondary(name = "clear_cancel", text = rs(R.string.action_cancel)) { onEvent(PlannerEvent.ClearCancelled) } }
                Box(modifier = Modifier.weight(1f)) { Primary(name = "clear_confirm", text = rs(R.string.action_clear), danger = true) { onEvent(PlannerEvent.ClearConfirmed) } }
            }
            return@Column
        }
        Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(R.string.settings_title), color = ink, style = LpType.title, maxLines = 1, modifier = Modifier.weight(1f).probe("settings_title"))
            Key(name = "close", icon = R.drawable.ic_cross, description = rs(R.string.action_close), enabled = true) { onEvent(PlannerEvent.PopupClosed) }
        }
        Segments(
            items = LanguagePref.entries,
            selected = state.settings.language,
            width = (PanelSizes.SETTINGS_W - 32f).dp,
            enabled = true,
            name = { "lang_${it.tag}" },
            onSelect = { onEvent(PlannerEvent.LanguageSelected(it)) },
        ) { item ->
            when (item) {
                LanguagePref.SYSTEM -> Icon(painter = painterResource(R.drawable.ic_globe), contentDescription = rs(R.string.lang_system), modifier = Modifier.size(20.dp), tint = ink)
                LanguagePref.ZH -> Text(text = rs(R.string.lang_zh), color = ink, style = LpType.segment, maxLines = 1, softWrap = false)
                LanguagePref.EN -> {
                    val english = rs(R.string.lang_en)
                    Text(text = rs(R.string.lang_en_short), color = ink, style = LpType.segment, maxLines = 1, softWrap = false, modifier = Modifier.semantics { contentDescription = english })
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = rs(R.string.show_spacing), color = ink, style = LpType.title, maxLines = 1, modifier = Modifier.weight(1f))
            Switch(checked = state.settings.showSpacing, onCheckedChange = { onEvent(PlannerEvent.ShowSpacingToggled(it)) }, modifier = Modifier.probe("show_spacing"))
        }
        Secondary(name = "clear", text = rs(R.string.clear_plan), enabled = state.lamps.isNotEmpty()) { onEvent(PlannerEvent.ClearClicked) }
        Spacer(modifier = Modifier.weight(1f))
        Text(text = rs(R.string.about) + " · " + rs(R.string.about_body, BuildConfig.VERSION_NAME), color = grey, style = LpType.label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("about"))
    }
}
