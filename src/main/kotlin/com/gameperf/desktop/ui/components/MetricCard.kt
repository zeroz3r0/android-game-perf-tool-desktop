package com.gameperf.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gameperf.desktop.ui.theme.*

@Composable
fun MetricCard(
    label: String,
    value: String,
    color: Color = Cyan,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(DarkCard, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = TextDim, fontSize = 10.sp)
        }
    }
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        color = Cyan,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}
