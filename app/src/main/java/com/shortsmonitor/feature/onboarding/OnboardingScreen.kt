package com.shortsmonitor.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shortsmonitor.app.R
import com.shortsmonitor.core.design.components.OutlinedActionButton
import com.shortsmonitor.core.design.components.PrimaryActionButton

/**
 * C단계 온보딩 화면.
 * 최초 실행에서만 표시되며, 온보딩 완료 여부는 DataStore에 저장한다.
 * 기능 안내는 원인을 단정하지 않는 표현만 사용한다.
 */
@Composable
fun OnboardingScreen(
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPrivacy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        OnboardingFeatures.forEach { feature ->
            OnboardingFeatureRow(
                title = stringResource(feature.titleRes),
                icon = feature.icon,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
        }

        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.onboarding_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        PrimaryActionButton(
            text = stringResource(R.string.onboarding_start),
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedActionButton(
            text = stringResource(R.string.onboarding_privacy),
            onClick = { showPrivacy = true },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text(stringResource(R.string.privacy_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.privacy_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
        )
    }
}

private data class OnboardingFeature(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
)

private val OnboardingFeatures = listOf(
    OnboardingFeature(R.string.onboarding_feature_webview, Icons.Outlined.PlayArrow),
    OnboardingFeature(R.string.onboarding_feature_recording, Icons.Outlined.List),
    OnboardingFeature(R.string.onboarding_feature_detection, Icons.Outlined.Warning),
    OnboardingFeature(R.string.onboarding_feature_profile, Icons.Outlined.Person),
    OnboardingFeature(R.string.onboarding_feature_reset, Icons.Outlined.Refresh),
    OnboardingFeature(R.string.onboarding_feature_local, Icons.Outlined.Lock),
)

@Composable
private fun OnboardingFeatureRow(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
