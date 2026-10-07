<div align="center">
  <h1>ClBI</h1>
  <p>面向开发 · 逆向 · 编程 · 聊天的 AI 助手</p>
  <p>一个基于 <a href="https://github.com/rikkahub/rikkahub">RikkaHub</a> 的增强分支</p>
</div>

## 关于

ClBI 是一个原生 Android LLM 客户端，在 RikkaHub 的基础上做了大量自研增强，专注
开发、逆向、编程与聊天场景。内置全离线逆向工具链，不需要联网即可完成二进制 / APK / DEX / SO
的静态分析。

> [!WARNING]
> 本项目是 RikkaHub 的分支（fork）。请仅在可信来源获取 APK，避免隐私泄露或过度权限请求。

## 增强功能

### 逆向工具（全离线自研）

- **radare2 内置引擎**：离线内置 r2 / rabin2 / rasm2 / rahash2 / radiff2 / rafind2 / rax2，
  支持文件信息、头/段/节、导入导出符号、入口点、依赖库、字符串、类、哈希、反汇编、函数、
  交叉引用、字节/字符串搜索、汇编/反汇编、二进制差异。
- **APK / DEX 工具箱**：解包、清单解析、DEX 信息、字符串、类、反汇编 smali、反编译 Java、
  组装 DEX、纯 Java 重打包 APK。
- **逆向工具箱**：base64 / hex / URL / HTML 编解码、MD5 / SHA / HMAC / CRC32、AES 加解密、
  JWT 解析、指标提取（IPv4 / IPv6 / 域名 / 邮箱 / 密钥）、XOR 爆破、熵、十六进制转储、
  端序转换、时间戳、UUID 等。
- **自研扩展**：DEX 字符串偏移定位与原地补丁、ELF 符号解析、SO 字符串提取、加固/壳检测。

### 交互式计划（自研进化版）

强大的多步任务规划与执行系统，支持全自动推进：

- **自动推进**：完成一步后 AI 自动标记下一步为进行中并继续执行，只在需要决策、重试耗尽时才停下问你。
- **子计划嵌套 + 独立进度**：步骤支持父子嵌套，父步骤显示基于子步骤的迷你进度条和百分比。
- **依赖图**：卡片内嵌紧凑节点连线图，按依赖深度分层，颜色区分状态（完成/进行中/阻塞/待定）。
- **执行证据**：每步完成时 AI 附上证据——输出片段、命令结果、文件路径，你可以直接验证。
- **失败自动重试**：步骤被阻塞时 AI 自动换方案重试（最多 3 次），记录重试原因，超过次数才求助用户。
- **动态计划调整**：AI 执行中发现计划不对可以自动删除/合并/新增步骤。
- **多模型协作**：步骤可指定不同模型执行（`modelHint`），快模型跑重复、强模型啃硬骨头。
- **负责人 / 标签 / 预估耗时**：步骤支持负责人、标签、预计分钟数与逾期提醒。
- **条件提问**：问题支持依赖条件，只显示相关的，防止互斥答案。
- **完成总结**：计划完成时生成收尾摘要——做了什么、跳过/阻塞了什么、后续建议。
- **Markdown 导出**：一键复制为 Markdown 清单（含勾选框、证据、耗时、阻塞原因）。
- **计划 HUD**：输入框上方折叠显示当前步骤与进度，无计划时自动隐藏。
- **消息入场动画**：新消息以弹性缩放 + 渐入效果进入聊天流。

### 玻璃拟态 UI

- **Liquid Glass 效果**：集成 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
  的 lens 折射 + blur + vibrancy 效果，输入栏与顶栏支持真实的液态玻璃质感。
- **玻璃强度自定义**：0.2~1.0 滑块调节折射深度与透明度。
- **性能模式**：一键关闭所有模糊/玻璃/光效动画，省电提速。
- **氛围光效**：计划卡片的呼吸光效 + 缓慢扫过的高光，性能模式下自动关闭。
- **状态弹跳动画**：计划步骤状态变化时图标 spring 弹跳。

### 宿主目录挂载

- 工作区（PRoot）直接把宿主共享存储根目录挂载到 `/sdcard`，AI 工具与终端可直接读写宿主文件：
  - `/storage/emulated/0`（即 `/sdcard`）→ `/sdcard`
- 一键授权页面简化 MANAGE_EXTERNAL_STORAGE 权限申请流程。

> [!WARNING]
> 直接挂载宿主目录会暴露宿主机真实文件，属于高权限挂载。写入需要授予
> 「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE），仅供本增强分支使用。

### 增强服务

- 设置页提供「增强服务」功能开关中心，统一管理本地工具与工作会话。
- 会话由 AI 调用并打开 App 创建，结束后自动清理。

## 其他功能

- Material You 设计 + 深色模式
- 多 AI 提供商（OpenAI / Google / Anthropic 兼容 API）
- 多模态输入（图片、文本、PDF、Docx）
- 基于 proot 的 Linux Agent 工作区
- Web 访问、MCP 支持
- Markdown 渲染、消息分支、搜索能力
- Prompt 变量、Agent 定制、记忆功能、AI 翻译
- 自定义 HTTP 请求头与请求体
- 角色卡导入

## 构建

本项目使用 GitHub Actions 云端构建 Debug APK，产物发布到 Releases 的 `debug` tag。

本地构建需要：

- Android Studio
- JDK 21（Gradle 自动下载 JetBrains Runtime）
- `app/google-services.json`（缺失时 CI 会生成 dummy 配置）

## 致谢

- [RikkaHub](https://github.com/rikkahub/rikkahub) — 原始项目
- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) — Liquid Glass 效果
- [chrisbanes/haze](https://github.com/chrisbanes/haze) — 模糊效果

## 许可证

本项目基于 [RikkaHub](https://github.com/rikkahub/rikkahub)，沿用
[GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）。
