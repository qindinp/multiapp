# DESIGN.md — MIUI 12 设计系统规范

> 设计语言：通感设计（Synesthesia Design）｜风格参考：小米 MIUI 12（Super Wallpaper / 深色模式 2.0 / 光锥动效）
> 适用场景：Android 多开管理应用 MultiApp 的 UI 实现，可平移至任意 Web / App 前端工程
> Token 命名：kebab-case，CSS 变量可直接引入样式层

---

## 1. Visual Theme & Atmosphere（视觉主题与氛围）

### 1.1 设计哲学

MIUI 12 的核心设计哲学是 **「轻快、生动、真实」** 三要素的融合：

- **轻量（Lightness）**：剥离多余的线条与分割框，用留白、圆角、阴影而非描边来划分层级；信息密度高但视觉负担低。
- **生动（Vividness）**：一切状态变化皆有反馈。动态图标、光锥动效、列表错峰入场，让界面"活"起来。
- **真实（Realism）**：遵循物理直觉的光影、弹性与惯性。阴影有明确"光源方向"（正上方偏置），按压有实体触感。

**通感设计在视觉上的体现**：将抽象的系统状态（数据、加载、切换）转译为具象的光影与空间变化——例如加载时卡片边缘泛起柔和光晕、页面转场模拟光线扫过的"光锥"效果、深色模式自动降低壁纸亮度形成"呼吸感"。视觉不是装饰，而是信息的第二次表达。

### 1.2 核心视觉特征

`大圆角卡片` · `柔和轻盈阴影` · `毛玻璃质感` · `光锥动效` · `深灰而非纯黑`

### 1.3 光影与质感倾向

- 卡片：大圆角（28px）+ 双层柔和阴影，无描边，靠投影分离层级
- 悬浮层（弹窗/抽屉）：毛玻璃背景 `backdrop-filter: blur(20px) saturate(180%)`
- 图标：线性风格，圆角端点，激活态填充
- 按压反馈：整体缩放 0.97 + 阴影收缩一档，模拟物理按压

### 1.4 Iconography（图标风格）

| 属性 | 规范 |
|------|------|
| 类型 | 线性为主（Linear），关键图标提供填充变体（Filled） |
| 设计网格 | 24×24 px，安全区内缩 2px |
| 描边宽度 | 2px（24px 网格基准，保持整像素避免亚像素发虚），32px 及以上升至 2.5px |
| 端点/拐角 | `stroke-linecap: round`；`stroke-linejoin: round` |
| 尺寸等级 | 16 / 20 / 24 / 28 / 32 / 40 px |
| 色彩 | 默认 `--color-text-secondary`；激活/选中 `--color-primary-500`；禁用 `--color-text-disabled` |
| 动态图标 | 遵循 MIUI 12 原则：图标可随状态"活着"（如时钟走动、电池呼吸），仅用于系统级关键图标，业务图标保持静态 |

### 1.5 Motion（动效原则）

| 动效类型 | 时长 | 缓动曲线 | 用途 |
|----------|------|----------|------|
| Micro（按压/开关） | 100–150ms | `cubic-bezier(0.25, 0.1, 0.25, 1)` | 按压、涟漪、图标切换 |
| Short（局部反馈） | 200ms | `cubic-bezier(0.25, 0.1, 0.25, 1)` | 卡片 hover、输入聚焦 |
| Base（元素转场） | 300ms | `cubic-bezier(0.2, 0.0, 0.0, 1.0)` | 弹窗、抽屉、列表项入场 |
| Long（页面转场） | 450–500ms | `cubic-bezier(0.2, 0.0, 0.0, 1.0)` | 页面切换"光锥"过渡 |
| Spring（物理回弹） | 600–900ms | `spring(stiffness 180, damping 22)` | 点赞动效、列表回弹、Tab 指示条 |

原则：① 动效必须与触发手势同源（从手指落点扩散）；② 列表错峰入场 stagger 30–50ms；③ 位移/缩放优先，慎用大范围模糊；④ 尊重系统「减少动态效果」设置，检测到后降级为 100ms 淡入淡出。

---

## 2. Color Palette & Roles（调色板与角色）

### 2.1 Primary — 品牌主色（MIUI 橙）

品牌识别色，源自 MIUI 品牌橙 `#FF6900`。用于主 CTA、激活态、品牌元素。

| Token | HEX | 场景 |
|-------|-----|------|
| `--color-primary-50` | `#FFF7EE` | 主色浅底（标签背景、选中项底色） |
| `--color-primary-100` | `#FFEBD4` | 浅橙底、进度条底色 |
| `--color-primary-200` | `#FFD4A8` | 图表辅助、渐变起止色 |
| `--color-primary-300` | `#FFBA73` | 禁用态高亮、深色模式强调色 |
| `--color-primary-400` | `#FF9A3D` | 次级强调、渐变中间色 |
| `--color-primary-500` | `#FF6900` | **主色基准**：主 CTA、开关、激活 Tab（大字场景） |
| `--color-primary-600` | `#E65C00` | 主按钮 hover/按压态 |
| `--color-primary-700` | `#C74E00` | **正文级橙**：橙色文字/图标（对比度 ≥4.5:1） |
| `--color-primary-800` | `#9E3D00` | 深色文本、页面头图文字 |
| `--color-primary-900` | `#7A2E00` | 最深橙，辅助文字 |

### 2.2 Accent — 强调/辅助色（光锥蓝）

链接、信息焦点、次级交互。与橙色互为补充，形成"品牌暖 + 系统冷"的平衡。

| Token | HEX | 场景 |
|-------|-----|------|
| `--color-accent-50` | `#E8F4FE` | 信息提示底色 |
| `--color-accent-100` | `#C8E7FD` | 选中列表底色 |
| `--color-accent-300` | `#5FB9F8` | 深色模式强调色 |
| `--color-accent-400` | `#2FA6F7` | 悬浮/hover 强调 |
| `--color-accent-500` | `#1C9BF6` | **强调基准**：链接、信息图标 |
| `--color-accent-600` | `#0B82E0` | 链接按压态、focus ring（白底 3.97:1 ✅） |
| `--color-accent-700` | `#0A6BBC` | 深色背景上的强调文字 |

### 2.3 Semantic — 语义色

| Token | HEX（浅色） | HEX（深色） | 场景 |
|-------|-------------|-------------|------|
| `--color-success` | `#00A862` | `#34C98A` | 成功状态、完成标记 |
| `--color-warning` | `#FF9F1C` | `#FFB84D` | 警告、待处理 |
| `--color-error` | `#F53535` | `#FF6B6B` | 错误、删除、未授权 |
| `--color-info` | `#1C9BF6` | `#5FB9F8` | 信息提示（同 accent-500） |
| `--color-success-bg` | `#E6F7EF` | `#12352A` | 成功提示底色 |
| `--color-warning-bg` | `#FFF3E0` | `#3A2E12` | 警告提示底色 |
| `--color-error-bg` | `#FFEBEB` | `#3D1B1B` | 错误提示底色 |
| `--color-success-700` | `#00824C` | — | 成功文字/实底按钮（白底 4.89:1，AA） |
| `--color-warning-700` | `#A05E00` | — | 警告文字（白底 5.13:1，AA） |
| `--color-error-700` | `#D92626` | — | 危险按钮背景、错误文字（白底 4.93:1，AA） |

> 语义色 -50 档底色直接复用 `-bg` token（success-bg / warning-bg / error-bg）；-700 档为浅色模式文字与实底按钮专用，深色模式语义文字走主色提亮策略（见 §2.5）。

### 2.4 Neutral — 中性灰阶（浅色模式）

| Token | HEX | 场景 |
|-------|-----|------|
| `--color-bg` | `#F5F5F7` | 页面主背景（MIUI 12 极浅灰） |
| `--color-surface` | `#FFFFFF` | 卡片/面板表面 |
| `--color-surface-variant` | `#F0F0F2` | 次级表面（输入框底、分区底） |
| `--color-border` | `#E8E8EC` | 分隔线、弱边框 |
| `--color-divider` | `#F0F0F3` | 更轻的分隔线（列表内） |
| `--color-text-primary` | `#26282C` | 主文字（标题、正文） |
| `--color-text-secondary` | `#737680` | 次要文字（描述、元信息；白底 4.53:1 ✅ AA） |
| `--color-text-tertiary` | `#8F939C` | 辅助文字（时间戳、占位；3.08:1，仅限非关键信息） |
| `--color-text-disabled` | `#C4C7CC` | 禁用文字 |
| `--color-text-on-primary` | `#FFFFFF` | 主色上的文字（大号场景） |

### 2.5 Dark Mode — 深色模式（MIUI 12 深色 2.0）

MIUI 深色模式的差异化特征：**拒绝纯黑**，使用深灰基底 `#17181A` 降低 OLED 眩光；文字与背景对比经专门调校。

| Token | HEX | 场景 |
|-------|-----|------|
| `--color-bg` | `#17181A` | 页面背景（非纯黑） |
| `--color-surface` | `#1F2124` | 卡片表面 |
| `--color-surface-variant` | `#26282C` | 次级表面 |
| `--color-border` | `#33363B` | 分割线/边框（提高明度保证可见） |
| `--color-divider` | `#2B2E33` | 列表内轻分隔线 |
| `--color-text-primary` | `#F5F6F7` | 主文字 |
| `--color-text-secondary` | `#A9ADB5` | 次要文字 |
| `--color-text-tertiary` | `#8A8F99` | 辅助文字（深底 5.5:1 ✅ AA） |
| `--color-text-disabled` | `#4A4E55` | 禁用文字 |
| `--color-text-on-primary` | `#FFFFFF` | 主色上的文字 |

**深色模式色彩策略**：品牌色整体提高明度一档使用（500→300/400 档），避免深底上橙色发闷；阴影改用纯黑更高 alpha（见 §6.2）；支持自动跟随系统 `prefers-color-scheme`。

---

## 3. Typography Rules（排版规则）

### 3.1 Font Family

```css
--font-family-cn: "MiSans", "PingFang SC", "HarmonyOS Sans SC", "Microsoft YaHei", "Noto Sans SC", sans-serif;
--font-family-en: "MiSans", "Inter", "Roboto", "Helvetica Neue", Arial, sans-serif;
--font-family-num: "MiSans", "SF Pro Display", "DIN Alternate", ui-monospace, monospace;
```

- 中文首选 **MiSans**（小米官方开源字体，继承小米兰亭，支持可变字重），回退 PingFang SC / 系统字体
- 数字与时间戳使用 `--font-family-num`，强制等宽语义（tabular-nums）对齐，避免跳动

### 3.2 Type Scale（移动端基准）

| 等级 | Token | Size | Weight | Line-Height | Letter-Spacing | 场景 |
|------|-------|------|--------|-------------|----------------|------|
| Display Hero | `--text-display` | 34px | 700 | 1.2 (40px) | -0.5px | 首页大标题、空状态主文案 |
| h1 | `--text-h1` | 28px | 600 | 1.3 (36px) | -0.3px | 页面标题 |
| h2 | `--text-h2` | 24px | 600 | 1.3 (32px) | -0.2px | 区块标题 |
| h3 | `--text-h3` | 20px | 600 | 1.35 (28px) | -0.1px | 卡片标题 |
| h4 | `--text-h4` | 18px | 600 | 1.4 (26px) | 0 | 列表项主标题 |
| h5 | `--text-h5` | 16px | 600 | 1.4 (24px) | 0 | 次级标题 |
| h6 | `--text-h6` | 14px | 600 | 1.45 (20px) | 0 | 小节标题、强调小标 |
| Body L | `--text-body-lg` | 16px | 400 | 1.6 (26px) | 0 | 正文大号（阅读场景） |
| Body | `--text-body` | 15px | 400 | 1.55 (24px) | 0 | 正文默认 |
| Body S | `--text-body-sm` | 14px | 400 | 1.5 (21px) | 0 | 列表描述 |
| Caption | `--text-caption` | 12px | 400 | 1.5 (18px) | 0.2px | 辅助说明、时间戳 |
| Nano | `--text-nano` | 11px | 400 | 1.45 (16px) | 0.3px | 角标、极小标签 |

### 3.3 桌面端字号放大

桌面端（≥1024px）在移动端基准上整体上浮一档：Display 34→44、h1 28→36、h2 24→30、h3 20→26、h4 18→22、Body 15→16。字重与行高保持不变，仅放大字号（见 §8.4 缩放规则）。

### 3.4 设计哲学

- 中文以 15–16px 为正文舒适区；行高取 1.5–1.6 保证中文阅读节奏
- 标题收紧字距（负值 letter-spacing）提升"块面感"，正文保持 0 保证可读性
- 字重阶梯收敛为 400/600/700 三档，避免滥用 500 造成界面"发虚"
- 数字统一 tabular-nums，时间、电量、计数对齐跳动最小化

---

## 4. Component Stylings（组件样式）

### 4.1 Button（按钮）

| 属性 | Primary | Secondary | Ghost | Danger |
|------|---------|-----------|-------|--------|
| 背景 | `--color-primary-500` | `--color-surface` | transparent | `--color-error-700` |
| 文字 | `#FFFFFF` | `--color-text-primary` | `--color-primary-700` | `#FFFFFF` |
| 边框 | none | 1px `--color-border` | 1px `--color-border`(可选) | none |
| 圆角 | `--radius-full` | `--radius-md` | `--radius-md` | `--radius-full` |
| 高度 | 48px（移动）/ 40px（桌面） | 同左 | 同左 | 同左 |
| 横向内边距 | 24px | 20px | 16px | 24px |
| 字号/字重 | 15px / 600 | 15px / 600 | 15px / 600 | 15px / 600 |

**状态**（以 Primary 为例）：
- Default：主色底 + `--shadow-1`
- Hover（仅桌面）：背景 `--color-primary-600`，阴影升 `--shadow-2`
- Pressed：背景 `--color-primary-700`，`transform: scale(0.97)`，阴影降 `--shadow-0`
- Disabled：背景 `--color-primary-100`，文字 `--color-text-disabled`，`pointer-events: none`
- Focus（键盘导航）：`outline: 2px solid var(--color-accent-600); outline-offset: 2px`（3.97:1 ✅ 满足 1.4.11 的 3:1）
- **Danger 状态**：Default 背景 `--color-error-700`（白字 4.93:1 ✅ AA）；Hover（桌面）背景 `#B81F1F`；Pressed 背景 `#A31F1F` + scale(0.97)；Disabled 背景 `--color-error-bg` + 文字 `--color-text-disabled`
- **对比度声明**：Primary 默认态（500 底 + 白字 2.9:1）属品牌豁免登记（见 §7.3 审计表）；高对比度场景将背景切 `--color-primary-600`（组件边界 3.27:1 ✅）

### 4.2 Card（卡片）

```css
.card {
  background: var(--color-surface);
  border-radius: var(--radius-xl);   /* 28px，MIUI 12 标志性大圆角 */
  box-shadow: var(--shadow-2);
  padding: 20px;
}
.card--pressable { transition: box-shadow .2s, transform .2s; }
.card--pressable:hover { box-shadow: var(--shadow-3); }   /* 仅桌面媒体悬停 */
.card--pressable:focus-visible { outline: 2px solid var(--color-accent-600); outline-offset: 2px; }
.card--pressable:active { box-shadow: var(--shadow-1); transform: scale(0.98); }
```

- 默认不描边，层级完全由阴影承担；需要分隔时用内部 1px `--color-divider` 分割线
- 卡片标题区：`--text-h3` + 16px 上下留白；正文区：`--text-body` 色 `--color-text-secondary`
- 分组卡片间距：16px；卡片内元素间距：12px

### 4.3 Navigation（导航栏）

**顶部导航栏（Top App Bar）**：
- 高度：48px + 状态栏（沉浸式延伸）
- 背景：`rgba(255,255,255,0.85)` + `backdrop-filter: blur(20px) saturate(180%)`；深色模式 `rgba(23,24,26,0.85)`
- 标题：居中 `--text-h4` 字重 600；返回键 24px 图标
- 分割：底部 0.5px `--color-border`（毛玻璃下保留极弱边界）
- 滚动态：内容滚动后背景不透明度升至 0.95、底部分割线显现，与内容建立明确分层（200ms 过渡）

**底部导航栏（Bottom Tab Bar）**：
- 高度：56px + 底部安全区（`env(safe-area-inset-bottom)`）
- Tab 数：4–5 个，图标 24px + 文字 11px（Nano）
- 激活态：图标与文字 `--color-primary-500`，图标切换填充变体，指示条 30ms 弹性位移动效
- 禁用/未激活：`--color-text-tertiary`

### 4.4 Input（输入框）

- 容器：高 48px，圆角 `--radius-sm`(8px)，背景 `--color-surface-variant`，无边框
- Focus：背景转 `--color-surface`，外包 2px `--color-accent-600` 边框 + `--shadow-1`
- Placeholder：`--color-text-tertiary`；错误态：边框 `--color-error` + 提示文字 12px
- Disabled：背景降透明度（表面层 0.5）、文字 `--color-text-disabled`、`pointer-events: none`

### 4.5 Badge / Tag（标签）

- 背景 `--color-primary-50`，文字 `--color-primary-700`，圆角 `--radius-full`，padding 4px 10px，字号 12px/500
- 语义变体：success / warning / error 使用对应 -50 档底色（复用 `-bg` token）+ 700 档文字（`--color-success-700` / `--color-warning-700` / `--color-error-700`）

### 4.6 Modal / Dialog（弹窗）

- 遮罩：`rgba(0,0,0,0.45)`（深色 `rgba(0,0,0,0.65)`）
- 内容区：宽 312px（移动）/ 420px（桌面），圆角 `--radius-lg`(16px)，`--shadow-5`，背景 `--color-surface`
- 入场：300ms，`translateY(24px) → 0` + `opacity 0→1`，标准曲线 `cubic-bezier(0.2, 0, 0, 1)`
- 关闭按钮：40×40px 点击区，图标 16px

---

## 5. Layout Principles（布局原则）

### 5.1 Spacing System（间距系统）

4px 基准倍数系统：

| Token | 值 | 场景 |
|-------|-----|------|
| `--space-1` | 4px | 图标与文字间距 |
| `--space-2` | 8px | 紧凑元素间距 |
| `--space-3` | 12px | 卡片内元素间距 |
| `--space-4` | 16px | 移动端页面边距、卡片内边距 |
| `--space-6` | 24px | 区块间距、桌面边距 |
| `--space-8` | 32px | 大区块分隔 |
| `--space-12` | 48px | 页面级留白 |
| `--space-16` | 64px | 超大留白（Hero 区） |

### 5.2 Grid System（栅格）

| 断点 | 容器宽 | 列数 | 列间距 | 边距 |
|------|--------|------|--------|------|
| 移动 <640px | 100% | 4 | 16px | 16px |
| 平板 640–1023px | 100% | 8 | 16px | 24px |
| 桌面 1024–1439px | 960px | 12 | 20px | 32px |
| Wide ≥1440px | 1200px | 12 | 24px | 32px |

- 卡片跨列规则：移动端 1 列（2 列仅限数据密集小卡），桌面端按 12 列取 3/4/6/12 等份
- 栅格嵌套不超过 2 层

### 5.3 Container 与 Section 间距

```css
.container { max-width: 1200px; margin-inline: auto; padding-inline: var(--space-4); }
@media (min-width: 1024px) { .container { padding-inline: var(--space-6); } }
.section { padding-block: var(--space-6); }
.section--hero { padding-block: var(--space-12) var(--space-8); }
```

### 5.4 留白哲学

MIUI 12 用"留白代替分割线"：列表项间优先用 12–16px 间距而非线框；卡片内部 20px 内边距建立呼吸感；页面顶部 Hero 区保留 48px 以上留白承接状态栏与标题。**宁可加大间距，不要加边框。**

---

## 6. Depth & Elevation（深度与层级）

### 6.1 Radius System（圆角）

| Token | 值 | 场景 |
|-------|-----|------|
| `--radius-xs` | 4px | 标签内角、进度条 |
| `--radius-sm` | 8px | 输入框、小按钮、缩略图 |
| `--radius-md` | 12px | 标准按钮、次级卡片 |
| `--radius-lg` | 16px | 弹窗、大按钮、内嵌面板 |
| `--radius-xl` | 28px | **MIUI 12 标志性大圆角**：主卡片 |
| `--radius-full` | 999px | 胶囊按钮、开关、Tab 胶囊、头像 |

原则：圆角随面积增大而增大（面积越大圆角越圆）；同一卡片内只使用一个主圆角等级。

### 6.2 Shadow System（阴影层级）

光源方向：正上方偏置（y 轴偏移），体现"悬浮物受光"的物理直觉。浅色模式阴影色 `rgba(23, 24, 26, α)`：

| Token | box-shadow | 用途 |
|-------|-----------|------|
| `--shadow-0` | `none` | 按压回收、扁平元素 |
| `--shadow-1` | `0 1px 2px rgba(23,24,26,.04), 0 2px 8px rgba(23,24,26,.04)` | 按钮、列表项 |
| `--shadow-2` | `0 2px 4px rgba(23,24,26,.05), 0 6px 16px rgba(23,24,26,.06)` | 普通卡片 |
| `--shadow-3` | `0 4px 8px rgba(23,24,26,.06), 0 10px 28px rgba(23,24,26,.08)` | 悬停卡片、下拉菜单 |
| `--shadow-4` | `0 8px 16px rgba(23,24,26,.08), 0 18px 44px rgba(23,24,26,.10)` | 侧边抽屉、气泡 |
| `--shadow-5` | `0 16px 28px rgba(23,24,26,.10), 0 32px 72px rgba(23,24,26,.12)` | 弹窗、全屏遮罩层 |

深色模式：阴影统一改用纯黑 `rgba(0,0,0,…)`，alpha 提升 30–40%（如 `--shadow-2` → `0 2px 4px rgba(0,0,0,.35), 0 6px 16px rgba(0,0,0,.45)`）。

### 6.3 Surface Layers（表面层级）

```
--color-bg  →  --color-surface  →  --color-surface-variant(嵌套)  →  overlay(遮罩)
   页面          卡片/面板           输入框/分区                   弹窗/抽屉
```

相邻层级变化至少需要「阴影 +1 档」或「圆角差异」，避免只靠颜色深浅分层。

### 6.4 Z-index Scale

| Token | 值 | 用途 |
|-------|-----|------|
| `--z-base` | 0 | 内容 |
| `--z-sticky` | 100 | 吸顶导航 |
| `--z-overlay` | 200 | 遮罩层 |
| `--z-drawer` | 300 | 抽屉 |
| `--z-modal` | 400 | 弹窗 |
| `--z-toast` | 500 | Toast / Snackbar |

### 6.5 Backdrop Effects（毛玻璃）

```css
--blur-appbar: blur(20px) saturate(180%);   /* 导航栏 */
--blur-sheet:  blur(24px) saturate(160%);   /* 底部弹层 */
--blur-card:   blur(12px) saturate(150%);   /* 悬浮小卡 */
```

---

## 7. Do's and Don'ts（设计规范与禁忌）

### 7.1 Do's

1. 用阴影+圆角分层，优先于描边分割——同层级组件不得同时出现边框与阴影
2. 正文/图标使用 700 档橙（`--color-primary-700`），500 档橙仅用于大号文字与图形场景
3. 所有可交互元素提供按压反馈（scale 0.97 + 阴影回收），动效时长严格对齐 §1.5
4. 卡片圆角遵循"面积越大圆角越圆"；主卡片统一 `--radius-xl`
5. 深色模式使用深灰底而非纯黑，所有灰色 token 必须走 §2.5 变量
6. 数字与时间统一 tabular-nums；中英文混排保持行高 ≥1.5
7. 图标描边统一 2px（24px 网格基准），激活态必须切换填充变体而非仅变色
8. 列表错峰入场 stagger 30–50ms，降低"整页瞬出"的生硬感

### 7.2 Don'ts

1. 禁止在 `--color-surface` 卡片上再叠加深色边框+重阴影（层级冲突）
2. 禁止使用纯黑 `#000` 作为深色模式背景；禁止把主色 500 用于小字号正文
3. 禁止 4px 以下间距（`--space-1` 为最小可用间距）
4. 禁止同时使用三种以上圆角等级于同一可视区块
5. 禁止添加与反馈无关的持续动画（呼吸、闪烁类），除非表达等待状态
6. 禁止阴影 spread 为正值的"实心投影"——破坏 MIUI 柔光质感
7. 禁止用透明度实现"灰色文字"（必须走 text-tertiary/disabled token，保证深色模式可调）
8. 禁止键盘焦点态缺失——任何可交互元素必须有可见 focus ring

### 7.3 Accessibility（可访问性）

- **对比度**：正文/图标 ≥ 4.5:1（AA），大字号（≥24px 或 19px+bold）≥ 3:1，UI 组件边界 ≥ 3:1（WCAG 1.4.11）。关键 token 实测对比度审计表：

| Token（浅色模式） | 背景 | 对比度 | 结论 |
|------|------|--------|------|
| `--color-text-primary` `#26282C` | `#FFFFFF` | 14.8:1 | ✅ AA 主文字 |
| `--color-text-secondary` `#737680` | `#FFFFFF` | 4.53:1 | ✅ AA 次要文字 |
| `--color-text-tertiary` `#8F939C` | `#FFFFFF` | 3.08:1 | ⚠️ 仅限非关键信息（占位/时间戳），关键信息一律用 secondary+ |
| `--color-primary-700` `#C74E00` | `#FFFFFF` | 4.65:1 | ✅ AA 橙色文字专用 |
| `--color-accent-600` `#0B82E0` | `#FFFFFF` | 3.97:1 | ✅ 3:1 链接与 focus ring |
| `--color-success-700` `#00824C` | `#FFFFFF` | 4.89:1 | ✅ AA |
| `--color-warning-700` `#A05E00` | `#FFFFFF` | 5.13:1 | ✅ AA |
| `--color-error-700` `#D92626` | `#FFFFFF` | 4.93:1 | ✅ AA danger 按钮 |
| 白字 on `--color-primary-500` `#FF6900` | `#FF6900` | 2.9:1 | ⚠️ 品牌豁免（CTA 按钮） |
| 橙底 `--color-primary-500` vs 页面 `#F5F5F7` | — | 2.65:1 | ⚠️ 组件边界不达 3:1，高对比度模式切 600 底（3.27:1 ✅） |

| Token（深色模式） | 背景 | 对比度 | 结论 |
|------|------|--------|------|
| `--color-text-primary` `#F5F6F7` | `#17181A` | 16.3:1 | ✅ AA |
| `--color-text-secondary` `#A9ADB5` | `#17181A` | 7.9:1 | ✅ AA |
| `--color-text-tertiary` `#8A8F99` | `#17181A` | 5.5:1 | ✅ AA |

**品牌豁免登记**：主 CTA 按钮默认态（primary-500 底 + 白字 2.9:1）对比度低于 AA，属品牌色标识豁免（WCAG 1.4.3 品牌例外，与 MIUI 系统自身行为一致）。合规退路：① 高对比度模式将背景切为 `--color-primary-600`（组件边界 3.27:1 ✅）；② 或叠加 1px 深色描边勾勒边界。交互反馈通过按压加深（600→700）传达，不依赖初始对比度。

- **触控目标**：交互元素 ≥ 48×48px（44px 为绝对下限），相邻目标间距 ≥ 8px；图标按钮用 40px 视觉 + 48px 点击热区
- **焦点与动效**：提供可见 focus ring（accent-600 2px）；支持 `prefers-reduced-motion`，降级为 100ms 淡入
- **文案规范**：① 中文为主，术语统一（多开/分身/宿主机全站一致）；② 按钮文案用动词开头（"新建分身"而非"新建"）；③ 错误文案三要素：发生了什么 + 原因 + 如何解决；④ 数字用半角，单位留空格（"512 MB"）；⑤ 禁止全大写堆叠，中文不用斜体

---

## 8. Responsive Behavior（响应式行为）

### 8.1 Breakpoints

| 断点 | 范围 | 布局策略 |
|------|------|----------|
| `--bp-mobile` | <640px | 4 列栅格，底部 Tab 导航，卡片单列 |
| `--bp-tablet` | 640–1023px | 8 列栅格，顶部导航可容纳次级操作 |
| `--bp-desktop` | 1024–1439px | 12 列栅格，侧边栏 + 顶栏，卡片 2–4 列 |
| `--bp-wide` | ≥1440px | 12 列栅格，最大容器 1200px，留白加大 |

### 8.2 Touch Targets

- 移动端全部交互元素 ≥ 48×48px；文字链可降至 44px 高但宽度 ≥ 60px
- 桌面端鼠标目标 ≥ 32×32px，hover 态必达
- 底部 Tab 栏随 `env(safe-area-inset-bottom)` 抬升
- 安全区：页面声明 `viewport-fit=cover`；刘海/挖孔机型顶部内容避开 `env(safe-area-inset-top)`（导航栏自动延伸），横向避开左右 inset；统一消费 `--safe-area-top` / `--safe-area-bottom` 变量，禁止硬编码

### 8.3 折叠策略（Collapse）

- 导航：桌面侧边栏 → 平板收窄为图标栏 → 移动转为底部 Tab
- 卡片网格：12 列 3 卡 → 8 列 2 卡 → 4 列单卡
- 弹窗：桌面居中浮层 → 移动转为底部抽屉式（全宽、顶部圆角 `--radius-xl`）
- 表格：移动端转卡片化堆叠（每行变卡片）

### 8.4 Font Scaling

- 移动→桌面字号上浮一档（§3.3），以 640px 为切换点
- 禁止用 JS 缩放字号；使用 clamp() 渐进过渡：`font-size: clamp(15px, 1vw + 12px, 16px)`
- 断点切换时字号跳变需同步调整行高，防止重排跳动
- 支持浏览器 200% 文本缩放（WCAG 1.4.4）：布局用相对单位与弹性容器，缩放后无截断、无重叠、无横向滚动

---

## 9. Agent Prompt Guide（AI 代理提示指南）

### 9.1 Quick Reference

> 设计 Token 全部为 CSS 变量（kebab-case），引入 `DESIGN.md` 定义的变量表即可无侵入落地。核心约束速查：卡片 `radius-xl + shadow-2 + 无边框`；主 CTA `primary-500 胶囊 + 白字`（高对比度场景切 600 底）；深色模式走 `data-theme="dark"` 变量覆盖；动效 100/200/300/450ms 四档 + 标准曲线；触控 ≥48px；橙色正文一律用 700 档；focus ring 一律 `accent-600`；对比度速查见 §7.3 审计表。

### 9.2 Component Prompts

1. **主操作按钮**："生成一个 Primary Button，胶囊圆角，橙底 #FF6900 白字，高 48px，含默认/按压(scale .97+阴影回收)/禁用(浅橙底灰字)三态，键盘焦点环 accent-600 蓝 2px。"
2. **MIUI 风格卡片**："生成列表卡片，圆角 28px，背景 --color-surface，阴影 --shadow-2，无边框，内边距 20px，标题区 h3 + 描述区 14px 次级色，按压时阴影降一档。"
3. **底部 Tab 导航**："生成 4 tab 底部导航，高 56px+安全区，图标 24px 线性、激活切填充变体且文字/图标变橙 #FF6900，指示条 30ms 弹性移动，未激活 tab 用 --color-text-tertiary。"
4. **顶部应用栏**："生成毛玻璃顶部栏，高 48px+状态栏，背景 rgba(255,255,255,.85)+blur(20px) saturate(180%)，居中标题 h4/600，底部 0.5px 弱分割线，深色模式自动切深灰。"
5. **表单输入框**："生成输入框，高 48px 圆角 8px，底 --color-surface-variant 无边框，聚焦转白底+2px accent-600 边框+shadow-1，placeholder 用 --color-text-tertiary，错误态红框+12px 提示，禁用态降透明+灰字。"
6. **弹窗**："生成对话框，宽 312px（移动）/420px（桌面），圆角 16px，shadow-5，入场 300ms 上移淡入，遮罩 rgba(0,0,0,.45)，关闭按钮 40px 热区。"
7. **状态标签**："生成 Badge，主色浅底 primary-50 + 文字 primary-700，胶囊圆角，padding 4px 10px，字号 12px/500，并提供 success/warning/error 语义变体。"

### 9.3 Iteration Guide

1. 先引变量表再写样式，任何硬编码色值都要能对应到 token
2. 卡片默认无边框，需要分隔时先用内部分割线，再加边框是最后手段
3. 动效参数只从 §1.5 四档时长 + 两条曲线中取，不引入新曲线
4. 深色模式不是"黑色背景"，必须走 §2.5 变量组，检查文字层级对比
5. 任何橙色文字自动落 700 档，除非确认是 ≥24px 大字号
6. 阴影必须双层（y 偏移+柔化），禁止 spread 正值
7. 触控目标不足 48px 时，用 ::before 扩展热区而非改视觉尺寸
8. 图标按 24px 网格 2px 描边实现，放大尺寸时描边同步升至 2.5px
9. 每次布局改动同时给出 <640 / ≥1024 两档的栅格归属（列数+边距）
10. 列表入场统一 stagger 30–50ms，勿用整页整体动画
11. 完成组件后自查焦点态：键盘可到达、focus ring 可见
12. 中英混排标题检查行高 ≥1.3，正文 ≥1.5
