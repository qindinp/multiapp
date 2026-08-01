# SECURITY.md

## 漏洞披露政策

MultiApp 项目重视安全性。若您发现安全漏洞（包括但不限于：guest 隔离绕过、Binder 权限提升、
Provider/进程槽位注入、数据目录越权访问），请按以下流程报告。

### 报告渠道

- **首选**：发送邮件至项目维护者，主题前缀 `[SECURITY]`。
- **备选**：在 GitHub 仓库创建 **private advisory**（若仓库启用了该功能），或通过 issue 系统
  提交（**不要**在公开 issue 中包含漏洞细节或 PoC，先私信维护者确认）。

### 报告内容

请包含：
1. 影响的版本/commit（`git rev-parse HEAD`）
2. 漏洞类型与危害描述
3. 复现步骤（最小化）
4. 设备环境（Android 版本 / ROM / ABI）
5. 可选：修复建议或 PoC

### 响应承诺

| 阶段 | 时限 |
|------|------|
| 确认收到 | 3 个工作日 |
| 初步分析 / 严重性定级 | 7 个工作日 |
| 修复发布（Critical/High） | 30 个日历日 |
| 修复发布（Medium/Low） | 下一个发布周期 |

严重性定级遵循 [CVSS v3.1](https://www.first.org/cvss/)。

### 负责任披露

- 我们承诺在修复发布前不公开漏洞细节。
- 我们感谢研究者在修复发布前保持漏洞细节私密。
- 披露者名单可在修复公告中致谢（可选）。

### 安全相关代码区域

项目中的安全关键代码（漏洞影响面最大的区域）：

- `core/engine`：Binder authority 矩阵、engine 进程权威写入
- `core/model`：24 槽进程契约、Provider route token、安装/实例记录模型
- `core/identity`：ContentResolver hook、Provider route token registry
- `app/container`：guest 进程 bootstrap、进程槽位解析、存储路径重定向
- `core/loader`：guest ClassLoader、AMS/Provider 拦截

### 已披露漏洞

暂无公开披露记录。全部安全修复通过成熟度审核的控制项（P0-SEC-01/P0-SEC-02/P1-SEC-01）跟踪。
