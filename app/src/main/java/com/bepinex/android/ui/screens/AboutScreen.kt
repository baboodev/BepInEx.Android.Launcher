package com.bepinex.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bepinex.android.R
import com.bepinex.android.ui.components.BugtopiaTitle

/**
 * Heartopia Bugtopia product page. Upstream project thanks live on [CreditsScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    onNavigateBack: () -> Unit,
    onNavigateToCredits: () -> Unit = {},
    showBackButton: Boolean = true
) {
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { BugtopiaTitle(title = stringResource(R.string.nav_about)) },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                ),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_bugtopia_logo),
                contentDescription = null,
                modifier = Modifier.size(96.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                stringResource(R.string.about_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                )
            ) {
                Text(
                    stringResource(R.string.about_version, versionName),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                stringResource(R.string.about_package),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            AboutActionButton(
                title = stringResource(R.string.about_credits),
                subtitle = stringResource(R.string.about_credits_desc),
                onClick = onNavigateToCredits
            )
            AboutActionButton(
                title = stringResource(R.string.about_news),
                subtitle = stringResource(R.string.about_news_desc),
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.about_url_telegram)))
                    )
                }
            )

            Spacer(Modifier.height(16.dp))

            Text(
                stringResource(R.string.about_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            Text(
                stringResource(R.string.about_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutActionButton(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
