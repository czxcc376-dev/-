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

### 计划与提问

- **交互式计划**：多步任务计划 + 向用户提问，支持子步骤、依赖、负责人与进度。
- **计划 HUD**：对话输入框上方折叠显示当前计划步骤与进度，无计划时自动隐藏。

### 外部目录挂载

- 通过系统文件选择器（SAF）授权外部目录，镜像到应用私有目录并挂载到 AI 工作区 `/mnt/<名称>`，
  AI 工具与终端可直接读写，支持双向同步与卸载。

### 增强服务

- 设置页提供「增强服务」功能开关中心，统一管理本地工具、工作会话与外部目录挂载。

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
- `app/google-services.json`（缺失时 CI 会生成 dummy 配置）

## 许可证

本项目基于 [RikkaHub](https://github.com/rikkahub/rikkahub)，沿用
[GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）。
