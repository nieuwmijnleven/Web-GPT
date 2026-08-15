package com.shortsmonitor.core.design.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shortsmonitor.core.design.OnError
import com.shortsmonitor.core.design.StatusError

/**
 * 공통 확인 하단 시트.
 * 세션 삭제·데이터 초기화 같은 위험한 작업의 확인 절차에 사용한다.
 * [destructive]가 true면 확인 버튼을 오류 상태 색상으로 표시한다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmationSheet(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            modifier = modifier,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                PrimaryActionButton(
                    text = confirmLabel,
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (destructive) {
                        ButtonDefaults.buttonColors(
                            containerColor = StatusError,
                            contentColor = OnError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedActionButton(
                    text = dismissLabel,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
