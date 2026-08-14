package com.nxfr.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxfr.android.ui.theme.AnimationPreference
import com.nxfr.android.ui.theme.LocalAnimationsEnabled
import com.nxfr.android.ui.theme.deckColors

@Composable
fun BreakerSwitch(
    isEngaged: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val deck = MaterialTheme.deckColors
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val isAppAnimated = LocalAnimationsEnabled.current
    val isSystemDisabled = AnimationPreference.isSystemAnimationDisabled(context)
    val animationsEnabled = isAppAnimated && !isSystemDisabled

    val animDuration = if (animationsEnabled) 140 else 0
    val trackWidth = 136.dp
    val thumbWidth = 62.dp

    val thumbOffset by animateDpAsState(
        targetValue = if (isEngaged) trackWidth - thumbWidth - 4.dp else 4.dp,
        animationSpec = tween(durationMillis = animDuration),
        label = "BreakerThumbOffset"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isEngaged) deck.signalBeam else deck.gridLineBright,
        animationSpec = tween(durationMillis = animDuration),
        label = "BreakerBorderColor"
    )

    val description = if (isEngaged) "Visibility Breaker, Powered On" else "Visibility Breaker, Powered Off"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(deck.surface, RoundedCornerShape(4.dp))
                .border(1.dp, deck.gridLine, RoundedCornerShape(4.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "VISIBILITY BREAKER",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEngaged) deck.textPrimary else deck.textSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isEngaged) "TCP 17394 · UDP BEACON [ACTIVE]" else "TRANSMITTER ISOLATED [OFFLINE]",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (isEngaged) deck.signalBeam else deck.textDim
                )
            }

            // Mechanical Toggle Switch with Accessible >= 48dp Touch Target
            Box(
                modifier = Modifier
                    .width(trackWidth)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = description }
                    .toggleable(
                        value = isEngaged,
                        role = Role.Switch,
                        onValueChange = { newState ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggle(newState)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Breaker Track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(deck.surfaceContainer, RoundedCornerShape(2.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // Breaker Thumb
                    Box(
                        modifier = Modifier
                            .offset(x = thumbOffset)
                            .width(thumbWidth)
                            .height(26.dp)
                            .background(
                                if (isEngaged) deck.signalBeam else deck.surfaceVariant,
                                RoundedCornerShape(2.dp)
                            )
                            .border(
                                1.dp,
                                if (isEngaged) deck.signalBeam else deck.gridLineBright,
                                RoundedCornerShape(2.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isEngaged) "PWR ON" else "OFF",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isEngaged) deck.rootBackground else deck.textSecondary
                        )
                    }
                }
            }
        }
    }
}
