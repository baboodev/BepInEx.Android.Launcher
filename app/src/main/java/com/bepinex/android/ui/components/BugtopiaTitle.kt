package com.bepinex.android.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bepinex.android.R

/** App-bar title with the Bugtopia mark beside the screen name. */
@Composable
fun BugtopiaTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_bugtopia_logo),
            contentDescription = null,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
