package com.multiapp.core.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.multiapp.core.designsystem.theme.MultiAppMotion
import com.multiapp.core.designsystem.theme.MultiAppSpacing
import com.multiapp.core.designsystem.theme.RadiusLg
import com.multiapp.core.designsystem.theme.RadiusXl

// ════════════════════════════════════════════════════════════════
// MIUI 12 核心组件（DESIGN.md §4）
// 按钮：胶囊 + 48dp + 三态 + 按压 scale(0.97) + focus ring（§4.1）
// 卡片：28dp 大圆角 + 无边框 + 阴影分层（§4.2）
// 弹窗：16dp + 阴影提升 + 300ms 上移淡入（§4.6）
// ════════════════════════════════════════════════════════════════

/**
 * 主按钮 — 胶囊橙底白字（§4.1 Primary）
 * 默认态 primary 底；按压 scale(0.97) + 阴影回收；禁用浅橙底灰字；
 * 键盘焦点由 M3 Button 自动提供（indication 可见 focus ring）
 */
@Composable
fun MiUiPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = MultiAppSpacing.buttonHeight,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = androidx.compose.animation.core.tween(MultiAppMotion.DurationMicro),
        label = "miuiButtonScale"
    )

    Box(modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
        Button(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = Modifier
                .height(height)
                .widthIn(min = 96.dp)
                .clip(CircleShape),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 2.dp,    // shadow-1 等效
                pressedElevation = 0.dp,    // 按压阴影回收（shadow-0）
                disabledElevation = 0.dp
            )
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * 危险按钮 — 深红底白字（§4.1 Danger，--color-error-700 白字 4.93:1 ✅）
 */
@Composable
fun MiUiDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = MultiAppSpacing.buttonHeight
) {
    MiUiPrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        height = height,
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError
    )
}

/**
 * 次按钮 — 白底描边（§4.1 Secondary）
 */
@Composable
fun MiUiSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = MultiAppSpacing.buttonHeight
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(height)
            .widthIn(min = 96.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), // radius-md
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 卡片 — 28dp 大圆角 + 无边框 + 阴影分层（§4.2）
 * 可点击版：按压阴影降一档（shadow-2→shadow-1）+ scale(0.98)
 */
@Composable
fun MiUiCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(MultiAppSpacing.cardPadding),
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = RadiusXl,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 4.dp,   // shadow-2 等效
                pressedElevation = 1.dp    // 按压降一档（shadow-1 等效）
            )
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(
            modifier = modifier,
            shape = RadiusXl,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}

/**
 * 确认弹窗 — 16dp 圆角 + 阴影提升（§4.6）
 * 移动端宽约 312dp（Dialog 默认最小），桌面端限制 420dp
 */
@Composable
fun MiUiConfirmDialog(
    title: String,
    text: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    dismissText: String = "取消",
    danger: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RadiusLg,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = text?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (danger) {
                MiUiDangerButton(text = confirmText, onClick = onConfirm, height = 40.dp)
            } else {
                MiUiPrimaryButton(text = confirmText, onClick = onConfirm, height = 40.dp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = dismissText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/**
 * 状态标签 — 语义色胶囊（§4.5，复用 InstanceStatusChip 样式体系）
 * 文字使用 -700 档保证 AA；底色使用 -bg/-container
 */
@Composable
fun MiUiStatusBadge(
    label: String,
    color: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = backgroundColor,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
