package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CarrierAirtel
import com.example.ui.theme.CarrierBsnl
import com.example.ui.theme.CarrierJio
import com.example.ui.theme.CarrierOther
import com.example.ui.theme.CarrierVi

@Composable
fun CarrierBadge(
    carrier: String,
    tech: String = "4G",
    modifier: Modifier = Modifier
) {
    val carrierColor = when (carrier.lowercase()) {
        "jio" -> CarrierJio
        "airtel" -> CarrierAirtel
        "vi", "vodafone", "idea" -> CarrierVi
        "bsnl" -> CarrierBsnl
        else -> CarrierOther
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(carrierColor.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(carrierColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$carrier $tech",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
    }
}
