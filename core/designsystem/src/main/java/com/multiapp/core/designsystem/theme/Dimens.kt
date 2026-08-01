package com.multiapp.core.designsystem.theme

import androidx.compose.ui.unit.dp

// ════════════════════════════════════════════════════════════════
// MultiApp Spacing — 4px 基准间距体系（DESIGN.md §5.1 / §7）
// ════════════════════════════════════════════════════════════════

object MultiAppSpacing {
    val space1 = 4.dp    // --space-1 图标与文字间距
    val space2 = 8.dp    // --space-2 紧凑元素间距
    val space3 = 12.dp   // --space-3 卡片内元素间距
    val space4 = 16.dp   // --space-4 移动端页面边距、卡片内边距
    val space6 = 24.dp   // --space-6 区块间距、桌面边距
    val space8 = 32.dp   // --space-8 大区块分隔
    val space12 = 48.dp  // --space-12 页面级留白
    val space16 = 64.dp  // --space-16 超大留白（Hero 区）

    /** 卡片内边距（§4.2） */
    val cardPadding = 20.dp

    /** 触控目标最小尺寸（DESIGN.md §7.3 / §8.2，≥48dp） */
    val touchTargetMin = 48.dp

    /** 按钮高度：移动 48dp / 桌面 40dp（§4.1） */
    val buttonHeight = 48.dp

    /** 顶部导航栏高度（§4.3，不含状态栏） */
    val appBarHeight = 48.dp

    /** 底部导航栏高度（§4.3，不含底部安全区） */
    val bottomBarHeight = 56.dp
}
