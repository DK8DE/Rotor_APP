package de.dk8de.rotorapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.dk8de.rotorapp.ui.theme.BridgeButton
import de.dk8de.rotorapp.ui.theme.BridgeButtonBorder
import de.dk8de.rotorapp.ui.theme.BridgeMuted
import de.dk8de.rotorapp.ui.theme.BridgeStop
import de.dk8de.rotorapp.ui.theme.BridgeText

enum class BridgeButtonTone {
    Normal,
    Stop,
}

@Composable
fun BridgeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: BridgeButtonTone = BridgeButtonTone.Normal,
    compact: Boolean = false,
) {
    val contentColor = when {
        !enabled -> BridgeMuted
        tone == BridgeButtonTone.Stop -> BridgeStop
        else -> BridgeText
    }
    val h = if (compact) 40.dp else 40.dp

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .defaultMinSize(minWidth = 1.dp, minHeight = h)
            .height(h),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, if (enabled) BridgeButtonBorder else BridgeButtonBorder.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = BridgeButton,
            contentColor = contentColor,
            disabledContainerColor = BridgeButton.copy(alpha = 0.55f),
            disabledContentColor = BridgeMuted,
        ),
        contentPadding = if (compact) {
            PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        } else {
            PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        },
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Medium,
            fontSize = if (compact) 12.sp else 14.sp,
            color = contentColor,
        )
    }
}
