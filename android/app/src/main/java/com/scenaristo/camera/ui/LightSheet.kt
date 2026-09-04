package com.scenaristo.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scenaristo.camera.domain.whitebalance.LightScenario
import com.scenaristo.camera.domain.whitebalance.presetsFor
import com.scenaristo.camera.theme.Tokens

/**
 * PRD 6.4's white balance, as the two questions a creator can actually answer.
 *
 * The scenario comes first and the temperature second, which is the whole point
 * of 6.4: someone knows whether there is daylight in their room and does not
 * know what a Kelvin is. Answering the first question removes half the presets,
 * and the ones left are the ones that could plausibly be right.
 *
 * The wording is UI-12's, and it is deliberately not the PRD's own: "Daylight in
 * the room" and "Lamps only" rather than "natural light present" and "artificial
 * light only". The user does not meet the word Kelvin before the preset value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightSheet(
    kelvin: Int,
    locked: Boolean,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // The scenario is a way of narrowing the list, not a setting: nothing is sent
    // when it changes, and the phone does not remember it. Which temperature is
    // in use is the only thing that survives, which is why it is the only thing
    // in the protocol.
    var scenario by remember {
        mutableStateOf(
            if (kelvin in presetsFor(LightScenario.ARTIFICIAL_LIGHT) && kelvin < 4500) {
                LightScenario.ARTIFICIAL_LIGHT
            } else {
                LightScenario.NATURAL_LIGHT
            },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.Panel) {
        Column(
            // Scrollable because this is a landscape app (UI-3): a bottom sheet
            // gets a few hundred pixels of height here, not most of a portrait
            // screen, and without this the last line is simply not reachable.
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Light", color = Tokens.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)

            if (locked) {
                // UI-6: a locked control says so rather than being discovered by
                // refusal. `Session` already nacks a settings change while
                // recording; this is the half that keeps the user from finding
                // out that way.
                Text(
                    "Locked while recording",
                    color = Tokens.Dimmer,
                    fontSize = 12.sp,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ScenarioTab("Daylight in the room", scenario == LightScenario.NATURAL_LIGHT, locked) {
                    scenario = LightScenario.NATURAL_LIGHT
                }
                ScenarioTab("Lamps only", scenario == LightScenario.ARTIFICIAL_LIGHT, locked) {
                    scenario = LightScenario.ARTIFICIAL_LIGHT
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                presetsFor(scenario).forEach { preset ->
                    PresetChip(preset, selected = preset == kelvin, enabled = !locked) { onPick(preset) }
                }
            }

            Text(
                "White balance stays locked once you pick one, so the colour does " +
                    "not drift in the middle of a take.",
                color = Tokens.Dim,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ScenarioTab(label: String, selected: Boolean, locked: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = when {
            locked -> Tokens.Dimmer
            selected -> Tokens.Text
            else -> Tokens.Dim
        },
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .background(
                if (selected) Tokens.Ground else Tokens.Panel,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (selected && !locked) Tokens.Amber else Tokens.Dimmer,
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = !locked, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun PresetChip(kelvin: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = "$kelvin K",
        color = if (selected) Tokens.Text else if (enabled) Tokens.Dim else Tokens.Dimmer,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .background(if (selected) Tokens.Ground else Tokens.Panel, RoundedCornerShape(8.dp))
            .border(
                if (selected) 2.dp else 1.dp,
                if (enabled) Tokens.Amber else Tokens.Dimmer,
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
