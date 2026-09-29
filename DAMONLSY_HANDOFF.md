# Damonlsy 项目交接文档

> 这是把 RikkaHub 改造成「Damonlsy」的完整交接记录。新开一个对话时，先读这个文件再继续。

## 基本信息

- **源码目录**：`D:\rikkahub`
- **包名**：`com.damonlsy.rikkahub`（debug 版带后缀 → `com.damonlsy.rikkahub.debug`）
- **应用名**：`Damonlsy`
- **基础版本**：**RikkaHub 2.5.4**（2026-09-27 已把官方 2.5.4 三路合并进我们的分支，`versionName=2.5.4` / `versionCode=189`，见下方「官方 2.5.4 合并」章节）；此前是 2.5.0 + 已移植的 2.5.2 更新
- **合并用的临时 git 仓库**：`D:\rikka-merge`（`master`=官方 2.5.0 base `7686c61`、`upstream`=2.5.4 `9764cb2`、`fork`=我们 `787cbb0`，合并提交 `623f1c8` + 修复 `1d27bcc`/`8e0d6a1`）
- **连接手机**：USB 调试，adb 在 `C:\Users\lenovo\AppData\Local\Android\Sdk\platform-tools\adb.exe`

## 构建 / 安装（重要）

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location D:\rikkahub          # ⚠️ 必须在 D:\rikkahub 下执行，否则报 "does not contain a Gradle build"
.\gradlew.bat assembleDebug --console=plain
# APK: D:\rikkahub\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
& "C:\Users\lenovo\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r "D:\rikkahub\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk"
```

**构建注意：**
- Gradle 发行版已升到 **9.6.0**（AGP 9.4.0 最低要求），仍走阿里云镜像 `mirrors.aliyun.com/macports/distfiles/gradle/gradle-9.6.0-bin.zip`（services.gradle.org 直连不通）。
- 整轮构建约 **13 分钟**（首次/全量），增量 1–2 分钟；PowerShell 工具超时会杀进程，**建议用 `Start-Process -RedirectStandardOutput ... -PassThru` 后台跑 + 轮询**。

**红线（务必遵守）：**
1. **只改代码，绝不推送/覆盖用户的设置数据文件**（之前覆盖过一次，用户很生气）。
2. 安装一律用 `adb install -r`（覆盖安装，保留数据）。
3. 不要动用户已配置的供应商、模型、API Key、MCP 等。
4. 数据库升级必须写 AutoMigration，不能丢数据。

## 环境侧的改动（为了让项目能编译）

- `settings.gradle.kts`、`build-logic/settings.gradle.kts`：加了**阿里云镜像**（否则依赖下载卡死）
- `gradle/wrapper/gradle-wrapper.properties`：Gradle 发行版改**阿里云镜像**（现在是 **9.6.0**，必须和 AGP 9.4.0 匹配）
- `gradle/gradle-daemon-jvm.properties` → 改名为 `.disabled`（否则强制下载 JetBrains JBR 卡死）
- `local.properties`：指向 Android SDK
- `app/google-services.json`：**占位文件**（Firebase 配置，原作者没开源）
- `web/build.gradle.kts`：**跳过 pnpm 构建**（没装 Node），生成占位页面
- `material3/material-color-utilities/kotlin`：手动补齐的子模块源码

## 已做的定制功能

### 1. 品牌
- 包名 `com.damonlsy.rikkahub`
- 应用名 `Damonlsy`（6 个语言文件）
- 图标：`C:\Users\lenovo\Pictures\Damonlsy.jpg` 生成的多密度图标

### 2. 关闭 RikkaHub 更新提示
- `utils/UpdateChecker.kt`：`checkUpdate()` 只返回 Loading，不再联网、不再提示

### 3. 日记本
- 侧边栏入口「日记本」（`Screen.Diary` / `Screen.DiaryDetail`）
- 卡片栅格 + 头像/名字（跟随系统）+ 日历筛选 + 自定义背景图（独立文件 `files/diary_background.txt`）
- 评论：回复、点赞、垃圾桶删除+确认、**无气泡框**
- 数据库表：`diaries`、`diary_comments`
- 相关文件：`ui/pages/diary/*`、`data/db/entity/Diary*.kt`、`data/db/dao/DiaryDAO.kt`、`service/DiaryService.kt`
- **AI 工具**：`diary_tool`（list/read/create/comment），在 `data/ai/tools/DiaryTools.kt`

### 4. 记账本
- 侧边栏入口「记账本」（`Screen.Ledger`）
- 两个小荷包（我的 / AI 的）**可切换 Tab**，头像+名字，总资产(2倍框) + 收入/支出/转赠，点击缩放 + 明细弹窗，转赠（我↔AI）
- 数据库表：`ledger_entries`
- 相关文件：`ui/pages/ledger/*`、`data/db/entity/LedgerEntity.kt`、`data/db/dao/LedgerDAO.kt`
- **AI 工具**：`ledger_tool`（list/add/transfer），在 `data/ai/tools/LedgerTools.kt`

### 5. 闹钟（已确认可用）
- **AI 工具**：`alarm_tool`，调用手机系统时钟（`AlarmClock.ACTION_SET_ALARM`），**会跳转到时钟界面**，**备注必填**（AI 自己决定内容）
- 权限：`com.android.alarm.permission.SET_ALARM`
- 文件：`data/ai/tools/AlarmTools.kt`

### 6. 应用锁（已按用户反馈重做）
- **AI 工具**：`app_lock_tool`（lock/unlock/list/protected）
- 白名单 = **受保护、永远不允许锁**：微信、学习通、支付宝、完美校园、胖乖生活、到梦空间、百度网盘
- AI 可锁/解其他任意应用；**lock 必须写备注**
- 拦截方式：**无障碍服务** `service/AppLockAccessibilityService.kt`。命中锁定后：
  1. 立即 `performGlobalAction(GLOBAL_ACTION_HOME)` 退回桌面（应用打不开/闪退）
  2. 弹**半透明**提示卡片（`PixelFormat.TRANSLUCENT` + 遮罩 = 主题 `scrim` 50% 左右，**外面能看见桌面**；居中圆角卡片用**当前主题配色**：`surfaceContainerHigh` 卡片底 / `onSurface` 标题 / `onSurfaceVariant` 次要文字 / `primary`+`onPrimary` 按钮）。配色逻辑与 `RikkahubTheme` 一致（`blockColors()`：动态取色或预设主题 + 读 `rikkahub.preferences` 的 `colorMode` 判断深浅色）
  3. ⚠️ 窗口格式必须用 `PixelFormat.TRANSLUCENT`；用 `OPAQUE` 会把半透明合成成黑色（踩过坑）
  3. `dispatchMediaKeyEvent(KEYCODE_MEDIA_PAUSE)` 暂停媒体播放（防止锁了还在响）
  4. `killBackgroundProcesses(pkg)` 兜底杀后台（需 `KILL_BACKGROUND_PROCESSES` 权限）
- 提示框只在用户打开**别的桌面 App**（`getLaunchIntentForPackage != null`）时才收起；桌面/系统 UI/输入法不收起；点「知道了」也收起
- 存储：`data/applock/AppLockStore.kt`（独立 SharedPreferences `app_lock`）
- 入口：**设置 → 应用锁**（跳系统无障碍授权）；无障碍里服务名是「**Damonlsy 应用锁**」
- 声明：`AndroidManifest.xml` 里 service（`exported="true"` + BIND_ACCESSIBILITY_SERVICE）+ `res/xml/accessibility_service_config.xml`

### 7. 气泡 / 消息
- 气泡风格（设置→显示）：**默认 / 黑白像素**（`userBubbleStyle` / `assistantBubbleStyle`）
- **分段分气泡**开关（`assistantSplitParagraphs`）
- 工具/思考链气泡缩到 2/3，但**字号单独放大**（`ChatMessage.kt` 的 density/fontScale 覆盖）
- 侧边栏功能按钮改成**横向可滑动**
- ⚠️ 之前做的「小恶魔框」「像素气泡」两种风格**已被用户否掉并删除**，不要再加回来

### 8. 其他
- 迁移包（Cherry 的 Damon 人格）：SOUL/USER 已写入助手系统提示词，FACT.md 13 条已写入记忆库

### 9. 定时查岗工作流
- 入口：**设置 → 定时查岗**（`Screen.Workflow` → `ui/pages/setting/WorkflowSettingPage.kt`）
- 开关 + 参数：`data/workflow/WorkflowStore.kt`（独立 SharedPreferences `workflow`）
  - `enabled` 总开关、`intervalMinutes` 查岗间隔（用户可调，默认 30）、`thresholdMinutes` 锁应用阈值（默认 60；**UI 文案已去吃醋化**，界面只说「锁应用阈值」）
- **调度：前台服务 + 协程计时器**（`service/WorkflowForegroundService.kt`，在 `Dispatchers.Default` 上 `delay`）
  - `service/WorkflowScheduler.kt`（Koin `createdAtStart` 观察开关，自动拉起/停止服务）
  - `receiver/BootReceiver.kt` + `RECEIVE_BOOT_COMPLETED`（开机重拉）
  - ⚠️ **关键坑**：ColorOS 会冻结后台进程，导致协程 `delay` 根本不触发。**必须**在系统里给 Damonlsy 开「自启动 + 允许后台运行 + 电池不受限制」，否则定时不准/不触发。开了之后实测精确到秒。
  - 曾尝试 AlarmManager：`setExactAndAllowWhileIdle` 被 ColorOS 降级成非精确（`windowLength≈45s`，会晚几十秒）；`setAlarmClock` 精确但状态栏有系统闹钟图标，**被用户否决**。相关文件 `service/WorkflowAlarmScheduler.kt` / `WorkflowAlarmReceiver.kt` 仍在但未使用（保留备选）。
- 执行：`service/WorkflowService.kt`
  - 查应用时长排行榜 `data/usage/AppUsage.kt`（`queryAppUsage`，复用屏幕使用时间口径，需「使用情况访问」权限）；吃醋阈值统计区间为**当天本地时间 00:00 到当前时间**，不是最近一次查岗间隔
  - 读当前屏幕文字：无障碍 `AppLockAccessibilityService.captureScreenText()`（配置已改 `canRetrieveWindowContent="true"` + `flagRetrieveInteractiveWindows`）
  - 用 fast 模型**流式**生成查岗消息（**非流式 `generateText` 在 opencode zen 的 `go` 端点会卡死，必须用 `streamText`**）。**2026-09-27 已去吃醋化**：prompt 改成「系统按固定间隔自动触发，这条消息写什么完全由你自己决定」，不再写死吃醋/撒娇口吻；AI 自己决定锁谁（严格输出 JSON `{"message","lock":[{"app","note"}]}`）
  - ⚠️ 模型偶发输出工具调用/思考（`<｜｜DSML｜｜...>`），已加**重试 + 过滤**，无效输出直接跳过该轮
  - 写进**当前助手的最近会话**（直接追加 assistant 消息，不走生成）
  - 联动应用锁 `AppLockStore.lock()`（白名单外、超阈值）
- 弹出：
  - `service/WorkflowNotifier.kt`：用 **MessagingStyle** 显示 **AI 头像 + AI 名字**（`utils/AvatarImage.kt` 的 `decodeAvatarBitmap` + `circularBitmap`），channel `workflow`
  - **悬浮头像**在 `AppLockAccessibilityService.showCompanion()`：右侧中央**圆形**按钮（48dp），钟摆摆动（±8°×3 次，400ms，ease-in-out），柔光（outline shadow），轻微震动，10 秒消失，点击打开会话
  - 复用 `TYPE_ACCESSIBILITY_OVERLAY`，**依赖无障碍服务**
- 权限：`VIBRATE`、`RECEIVE_BOOT_COMPLETED`、`SCHEDULE_EXACT_ALARM`、`USE_EXACT_ALARM`（已加）；通知 channel `workflow`
- ⚠️ **重装 APK 会被系统自动关掉无障碍**，需重新开启（临时可用 adb：`settings put secure enabled_accessibility_services com.damonlsy.rikkahub.debug/me.rerere.rikkahub.service.AppLockAccessibilityService` + `settings put secure accessibility_enabled 1`）
  - ⚠️ 屏幕读取靠无障碍文字节点：拿不到图片/视频/游戏画面，`FLAG_SECURE`（银行等）也读不到

### 10. Gemini 工具调用修复（已编译安装）
- 实际问题发生在 **Gemini 的 OpenAI 兼容中转路径**（`ChatCompletionsAPI`），日志显示中转站拒绝历史 `messages.reasoning_content`：`protocol conversion cannot preserve messages.reasoning_content`。
- `ChatCompletionsAPI.kt`：模型 ID 含 `gemini` 时，不回传历史 reasoning 内容，避免 Gemini 兼容网关协议转换失败。
- `ChatCompletionsStreamDecoder.kt`：工具调用按 `tool_calls[].index` 保持稳定 ID；工具名只发送首次增量；参数按累计快照计算 delta，避免工具名/参数重复拼接导致工具循环。
- AI 模块 `:ai:compileDebugKotlin` 与完整 `assembleDebug` 均已通过，APK 已使用 `adb install -r` 安装，用户设置数据保留。

### 11. 朋友圈（已编译安装，待用户验证）
- 入口：**侧边栏 → 朋友圈**（`Screen.Moments` → `ui/pages/moments/MomentPage.kt`，发动态在页内 `MomentComposer`）
- **两个版本并存（微信版是新增，不删简洁版），侧边栏两个入口，共用同一份数据/`MomentVM`**：
  - 「朋友圈」= 简洁版（`MomentPage.kt`）
  - 「微信朋友圈」= 微信版（`ui/pages/moments/MomentsWeChatPage.kt`，`Screen.MomentsWeChat`）：顶部封面大图（可自定义，点封面换图，存独立 SharedPreferences `moments` 的 `cover`）、封面右下用户头像+昵称、整页可滑动、每条右侧「···」菜单（赞/评论/收藏/删除）、**用户自己发的帖子昵称显示「我」**
- 用户和 AI 共用一条时间线，双方都能发动态、点赞、评论、收藏，头像昵称跟随系统（用户 = userNickname/userAvatar，AI = 当前助手 name/avatar）
- 数据表：`moments`（id/content/images JSON 数组/author/created_at）、`moment_likes`、`moment_comments`、`moment_favorites`（likes/favorites 用复合主键 `(moment_id, author)` 去重）
- 数据库：version 29 → 30（AutoMigration，新增 4 张表）
- DAO：`data/db/dao/MomentDAO.kt`（Flow + suspend 快照查询）
- AI 工具：`moments_tool`（list/read/create/like/unlike/comment/favorite/unfavorite），在 `data/ai/tools/MomentsTools.kt`，注册进 `ChatToolFactory`
- ⚠️ `MomentVM` 必须在 `di/ViewModelModule.kt` 注册（`viewModelOf(::MomentVM)`），否则进入页面会 `NoDefinitionFoundException` 崩溃
- UI：时间线 LazyColumn + 九宫格图片（1 张大图 / 多图 3 列）、点赞列表、评论列表（支持回复某人）、相对时间、点赞/评论/收藏按钮、删除
- 发动态：文字 + 选图（`GetMultipleContents` 最多 9 张，存 `FilesManager.createChatFilesByContents`）

### 12. 表情包与拍一拍（已编译装机）
- 数据库表 `pat_actions`，实体 `data/db/entity/PatActionEntity.kt`、DAO `PatActionDAO.kt`；`slot` 列区分**三槽位**（方式 / 动作 / 对象），`owner` 分用户 / AI 两个池（`PAT_AUTHOR_USER`、`PAT_AUTHOR_AI`）
- 数据库 version 31 → 32（AutoMigration 加 `slot` 列）
- 入口：**设置 → 表情包与拍一拍**（`ui/pages/setting/SettingStickerPatPage.kt`），空槽文案是「没关系，拍一拍那句话里就直接省掉这一段」
- **拍一拍语义（定稿）**：三个槽位**都可为空，空则该段在句子里直接省掉，不报错、不兜底**。`StickerPatUtil.kt`：`pickPatWord(): String`（空返回 `""`）、`pickPatWords(): Triple`；`buildPatTextSamples` 仅在该 owner 池全空时返回 `emptyList()`。文案**无空格**：`我$manner$action$target$part`（如「我用手轻轻捏了捏Damon」），AI 侧 `${aiName}$manner$action$target$part`
- **AI 工具** `pat`（`data/ai/tools/PatTools.kt`）：`pick(key, slot) = override(key) ?: pickPatWord(...)`，**不再 error**；返回值只有 `success` / `text`，**没有 `note` 字段**
- 表情包消息：`STICKER_MARKER_PREFIX` + `isStickerMessage` / `isPatMessage`（`ChatMessage.kt` 走 `PatMessageRow`，头像双击弹 `PatMenu`，`ChatMessageAvatar.kt`）
- `+` 面板里有表情包/拍一拍入口（`FilesPicker.kt` / `ChatInput.kt`）

### 13. 设备上下文注入（已编译装机，权限需手动授权）
- 让 AI 知道时间 / 电量 / 天气 / 最近使用的应用：`data/ai/transformers/DeviceContextTransformer.kt` 把 `<device_context>` 追加到 system 消息（没有 system 就新建一条 `isSynthetic` system 消息），挂在 `ChatService.inputTransformers` **末位**
- 实现：`utils/DeviceContext.kt`（拼块）、`utils/Weather.kt`（Open-Meteo 免 key + `LocationManager` 定位，定位/天气各缓存 10 分钟，WMO code 中文映射）、`data/usage/AppUsage.kt` 的 `queryRecentApps()`（`UsageEvents` 前台切换，排除桌面和本应用）
- 开关：`PreferencesStore.kt` 的 `DeviceContextSetting`（enabled/时间/电量/天气/最近应用 + 回看分钟数 + 条数），入口 **设置 → 设备上下文**（`SettingDeviceContextPage.kt`，`RouteActivity.Screen.SettingDeviceContext`）
- 权限：`ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION`（天气）+ `PACKAGE_USAGE_STATS`（最近应用）；**未授权就跳过该项，不报错**


- 当前 `version = 32`（迁移链完整，AutoMigration 1→2 … →31→32；含日记本/记账本、表情包+拍一拍三槽位、朋友圈 4 张表）
- ⚠️ **官方 2.5.4 的 `version = 25` 没有采用**：官方那条 24→25 的迁移对表结构**没有任何改动**（比对官方 24.json vs 25.json：8 张表的列/索引/外键完全一致），而且我们已有的共享表列与官方 25 完全相同（我们是 17 张表的超集）。所以冲突处取我们的 `AppDatabase.kt` + 我们的 `25.json`，用户数据链完全不受影响。

## 官方 2.5.4 合并（2026-09-27，已编译通过，待装机验证）

**做了什么**
1. 备份源码 → `D:\rikkahub-backup-20260926`（1642 文件，**`ai/**` 我方版本的回退点**）。
2. 官方 2.5.4 源码 → `C:\Users\lenovo\AppData\Local\Temp\opencode\rikkahub-2.5.4.zip`（46 MB，`ghproxy.net` 可下，`services.gradle.org`/`api.github.com` 直连全挂；仓库已从 `re-ovo/rikkahub` 迁到 **`rikkahub/rikkahub`**）。
3. 临时仓库 `D:\rikka-merge` 三路合并：base = `D:\rikkahub.zip`（官方 2.5.0）。

**只有 6 个冲突，处置如下（原则：我们赢，官方无损处并入，`ai/**` Gemini 链以官方为主）**

| 文件 | 处置 |
|---|---|
| `gradle/wrapper/gradle-wrapper.properties` | 取我们的（阿里云镜像），但版本号改成 **9.6.0**（AGP 9.4 强制） |
| `data/db/AppDatabase.kt` | **取我们的**：`version = 32` + 我们的完整迁移链；丢弃官方 `AutoMigration(24,25)`（它是空迁移，见上一节） |
| `app/schemas/.../25.json`（AA） | 取我们的（我们的 25 与官方 25 内容不同但不冲突，官方那份表结构没变） |
| `data/ai/transformers/WorkspaceReminderTransformer.kt` | 只有注释措辞差异，取我们的 |
| `res/values/strings.xml`、`values-zh/strings.xml` | 手工合：保留我们的（`app_lock_service_*`、`workspace_detail_*`、我们自己的 `regenerate_confirm_message` 文案）+ 补进官方新增的 17 条（时间提醒间隔 / 图片删除 / 输入栏背景效果） |
| 其余 44 个双方都改的文件 | git 自动合并（非重叠区域 = 官方无损改动），包括 `AndroidManifest.xml`、`PreferencesStore.kt`、`ChatService.kt`、`RouteActivity.kt`、`app/build.gradle.kts` |

**`ai/**` Gemini 链（按指示取官方 2.5.4）**：实际被官方改动的只有 `ChatCompletionsAPI.kt`（阿里云百炼改用 `reasoning_effort`、去掉 tool 消息的 `name` 字段、OpenAI 分支不再把 `none` 映射成 `low`）、`ResponseApiStreamDecoder.kt`（工具调用按 `output_index`/`call_id` 兜底解析）、`GoogleProvider.kt`、`ModelRegistry.kt` + 对应测试。**我们原来「模型 ID 含 `gemini` 就不回传历史 `reasoning_content`」的修复完好保留**（`ChatCompletionsAPI.kt:236/243` 的 `isGeminiCompatible`），但**中转站当时是挂的，Gemini 仍属未验证**；回退点 `D:\rikkahub-backup-20260926`。

**合并带来的新功能（官方）**：`ConversationSessionManager` 重构（`ChatService` 的会话管理全交给它）、会话标题自动去重 `title(n)`、置顶/移动助手元数据接口、时间提醒间隔设置、图片批量删除、输入栏背景效果（模糊/玻璃）、Response API 工具调用解析修复。

**踩到的坑**
- `ChatService.kt` 合并后少了 `import java.time.Instant`（我们 `flushPendingOutgoingMessages` 还在用 `Instant.now()`）→ 已补回。
- 用 PowerShell `git show ... | Out-String` 取文件会**按控制台编码解码 UTF-8，把中文/省略号写坏**（`…</string>` 变成 `?/string>`，XML 解析失败）。**取 git 内容必须用 `cmd /c "git show ref:path > file"` 走字节重定向**，读文件一律 `[IO.File]::ReadAllText(path, [Text.UTF8Encoding]::new($false))`。

## 关键文件速查
- 路由：`app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`（`Screen` 定义 + entry）
- 侧边栏：`ui/pages/chat/ChatDrawer.kt`
- 设置页：`ui/pages/setting/SettingPage.kt`（主）、`SettingPreferencesUIPage.kt`（显示）
- 设置模型：`data/datastore/PreferencesStore.kt`（`DisplaySetting`）
- 工具工厂：`data/ai/tools/ChatToolFactory.kt` + `di/AppModule.kt` + `di/DataSourceModule.kt` + `di/ViewModelModule.kt`
- 数据库：`data/db/AppDatabase.kt`

## 待办 / 用户已知诉求
- 应用锁的浮层效果**待用户验证**（灰色图标 + 助手名 + 备注）
- 定时查岗工作流**待用户验证**：开关在「设置 → 定时查岗」，需先开无障碍（读屏幕）和「使用情况访问」（读时长），改过无障碍配置可能要**重新开关一次**无障碍
- 朋友圈**待用户验证**：侧边栏入口，用户 + AI 发动态/点赞/评论/收藏，九宫格图片
- 用户说「后期会加很多工作流（定时任务之类）」——定时查岗是第一个，后续可能继续加
- 闹钟已确认可用

## 素材位置（用户的美化素材）
- `E:\ui美化素材\`（像素小图标 18 张 + 山竹素材(45).png 小恶魔框）

## 插件系统移植（OrangeChat → 本项目，2026-09-27，A+B 批已装机验证）

从上游 OrangeChat（AGPL v3，源码在 `C:\Users\lenovo\AppData\Local\Temp\opencode\orangechat-src\orangechat-master`）移植 QuickJS 插件系统，落在 `app/src/main/java/me/rerere/rikkahub/plugin/`：

- 引擎：dokar3（`io.github.dokar3:quickjs-kt:1.0.15`），沙箱在 `plugin/loader/PluginSandbox.kt`。
- 工具装配：`data/ai/tools/ChatToolFactory.createTools()` 里 `addAll(pluginToolProvider.getTools())`（createTools 是 suspend，可直接调 suspend 的 getTools）。
- 提示词注入：新增 `data/ai/transformers/PluginPromptTransformer`，挂进 `ChatService.inputTransformers`。
- 审批：原 `needsApproval = { true }` 改为 `{ !autoApproveTools }`，开关存在 DataStore key `plugin_auto_approve`（`PluginRepository`），插件页「审批」卡片可切换，改动即刻生效。
- 生命周期事件：`ChatService` 在 `saveConversation` 后发 `message_sent`、在 `handleMessageComplete` 发 `message_received`，fire-and-forget。
- UI：`plugin/ui/PluginViewModel.kt` + `PluginManagePage.kt`，入口在设置页「插件」（`Screen.SettingPlugins`，图标 HugeIcons.Package）。
- 依赖注入：`plugin/di/PluginModule`，已在 `RikkaHubApp.kt` 挂到 Koin modules。
- 已修的坑：
  - `runtime.evaluate<Unit>(code, name)` 会报 JsObject→Unit 转换失败，改成 `code + "\n;void 0;"`。
  - `data/ai/tools/ToolNaming.kt` 里 `"$MCP_PREFIX$serverName__$toolName"` 必须写成 `${serverName}__`，否则 Kotlin 把 `__toolName` 当作变量。
  - 插件里 `console.*` 原本未定义，已在 `BOOTSTRAP_JS` 注入 polyfill，并用 `runtime.function("__consoleLog")` 打到 logcat（tag `PluginSandbox`），验证通过。
- 实测（示例天气插件已种进 `files/plugins/com.orangechat.plugin.weather/`）：
  `Exported functions: [get_weather, get_weather_brief, get_weather_forecast]`、`Tool 'get_weather' registered successfully`。
- 尚未做：C 批完整 UI（详情页/文件页/声明式 UI/WebView、exoplayer、通知渠道）、D 批 hooks（MemoryBank、每日摘要回接）。

## 上下文压缩改造（2026-09-27，已编译 + 单测通过 + 已装机）

需求：压缩上下文后**聊天记录不能丢**，AI 只读取压缩后的内容。

- 不改 `conversation.messageNodes`，不碰 Room schema（不加表）。
- 新增 `data/datastore/ConversationCompressionStore.kt`：独立 DataStore（`conversation_compression`），按会话存
  `CompressedContext(summary, boundaryMessageId, compressedCount, updatedAt)`。
- `ChatService.compressConversation()` 重写：只生成摘要并写 store，不再改写历史；
  另加 `compressedContextFlow(conversationId)` 和 `clearCompressedContext(conversationId)`。
- 新增 `data/ai/transformers/CompressedContextTransformer.kt`：在 `inputTransformers` 的**第一位**，
  把边界之前的历史换成一段 `<compressed_context>` 摘要（插在 system prompt 之后，`isSynthetic = true`），
  其余消息原样发送。边界消息找不到时（被删除/切分支/已被 `contextMessageLimit` 从头截掉）
  按 `compressedCount` 兜底；折叠结果保证至少保留最后一条真实消息。
- 单测：`app/src/test/.../CompressedContextTransformerTest.kt`，9 项全绿（`:app:testDebugUnitTest`）。
- UI：压缩对话框（`CompressContextDialog`）新增「取消压缩（恢复完整上下文）」按钮，
  由 `ChatPage → FilesPicker → CompressContextDialog` 透传 `hasCompressedContext` / `onClearCompressedContext`；
  文案 `chat_page_compress_warning`、`chat_page_compress_context_desc` 已改成「不删记录」的表述（中英均已更新）。
- 待用户在真机验证：压缩后聊天记录仍完整可见 → 下一轮生成只发摘要 + 压缩点之后的消息 → 点「取消压缩」后恢复完整上下文。

## 学习模式 · 英语单词背诵（2026-09-28，已编译 + 239 单测全绿，待装机）

**功能**：侧边栏「学习模式」→ 今日学习统计（新学/复习/时长）+ 两张词库卡片（**高中必备词汇 / 大学四六级必备词汇，deck 列物理分开**）→ 点「开始学习」进卡片复习：点卡片 3D 翻转看释义，底部两个**缩放按键**（`ScaleButton`，按压弹到 0.92 倍）「不记得 / 记得」；SRS 间隔 0/1/3/7/14/30 天；离开页面自动记一条学习会话（时长/复习数/新学数）。

**AI 可见**：`study_status` 工具（`data/ai/tools/StudyTools.kt`）返回每个词库今日新学/复习词数、学习秒数、总词数/已学/已掌握——AI 在聊天里能直接问出用户学习状态。

**词库**：`app/src/main/assets/study/high_school.json`、`cet46.json`（各 120 词，含音标/词性释义/例句/译句，首次进页面自动入 Room；**种子词表，可按同格式扩充**）。

**实现要点**
- Room version 32 → 33（AutoMigration），新表 `study_words`（deck/word/phonetic/meaning/example/exampleMeaning/box/intervalDays/dueDay/lastReviewedAt/learnedAt）、`study_sessions`（deck/startedAt/durationMs/reviewedCount/learnedCount）。
- 液态玻璃：`GlassStyle.Material3`（注意 import 是 `dev.chrisbanes.haze.glass.material3.Material3`）+ `hazeGlass` + `GlassDefaults.optics`，`AssistantBackground(..., Modifier.hazeSource(hazeState))` 打底；词卡双面 3D 翻转用 `graphicsLayer { rotationY }` + 预旋 180° 的背面。
- 图标用 HugeIcons（`HugeIcons.GraduationCap` / `HugeIcons.School`，`me.rerere.hugeicons.stroke.*`）——**Lucide 没有 GraduationCap**。
- SRS 调度 `StudyScheduling`（纯函数可单测，4 个用例在 `StudySchedulingTest`）；新词答对 → box 1 明天复习（避免当天队列无限循环）。
- 注册点：`AppDatabase`(entities/version/autoMigration/dao) + `DataSourceModule`(studyDao + StudyRepository) + `ViewModelModule`(StudyVM) + `RouteActivity`(Screen.Study / Screen.StudyDeck) + `ChatDrawer` + `ChatToolFactory`(studyRepository) + `AppModule`。

**待用户真机验证**：侧边栏进学习模式 → 两张词库卡进度 → 进入复习 → 翻转卡片评分 → 退出后今日统计更新 → 聊天里 AI 能报学习状态。

## 微信式语音条 + 拨出语音通话 + AI 语音回复（2026-09-28 凌晨，已编译 + 235 单测全绿 + 已装机，待用户验证）

**功能**
1. **语音条输入**：输入栏右侧 `Mic01` 切换进语音模式（`VoiceBarInput.kt`）——按住说话、松开发送、上滑 64dp 取消、60s 上限自动发；麦克风权限未授权时先走 `PermissionManager` 申请。
2. **气泡内联播放**：AI/工具产出的 Audio 消息渲染成微信式语音条（`VoiceBarBubble.kt`，宽度 72+秒×3 dp，点按播放/暂停、7 格进度、时长优先读 metadata 兜底 MediaMetadataRetriever）；单例播放器 `VoiceBarPlayer.kt`（离屏自动停）。
3. **拨出语音通话**：顶栏 `Phone` 图标 → 全屏拟通话页（`CallOverlay.kt`：状态/计时/字幕/transcript、`PhoneOff01` 挂断、Back 即挂断、keepScreenOn、失败可 Retry）；挂断后 `ChatService.appendCallRecord()` 轮询等生成结束（≤60s）追加「通话 mm:ss」记录卡（`CallRecordRow`）；<1s 不留记录。
4. **AI 语音回复**：`AIVoiceReply.kt` 监听 `generationDoneFlow`，按 设置→语音→AI语音回复 三态（off / text_and_voice / voice_only，`DisplaySetting.aiVoiceReplyMode`，默认 off）对末条 assistant 非通话消息做 TTS→WAV→`saveUploadFromBytes`→Audio part 追加；voice_only 模式文字部分打 `hide_text` 标记并隐藏（切回文字模式即恢复）。语音会话激活时（通话中）不触发。
5. 相关：`VoiceSessionController` 加 `speakingText`（通话页字幕）、`ChatVM.handleCallEnded/appendAssistantMessageParts`、`ChatInput.voiceBarMode`（rememberSaveable）、`TTSAutoPlay` 条件加 `aiVoiceReplyMode == "off"`。

**实现要点 / 坑**
- `PartMetadata` 是 ai 模块的 `sealed interface`，**app 模块不能实现、不能同包扩展** → metadata 一律走 raw `JsonObject`（照抄 `StickerPatUtil` 模式），工具函数在 `utils/VoiceMessageUtil.kt`（`buildCallParts/isCallMessage/voiceAudioMetadata/voiceAudioDurationMs/isVoiceHiddenText/pcmToWav/probeAudioDurationMs/formatCallDuration`）。
- `Json.decodeFromJsonElement<T>(e)` reified 重载不存在；Long 没有 `.dp`（先 `.toInt()`）；`UIMessagePart.Audio` 只有 `url + metadata`，无 mimeType。
- `saveUploadFromBytes` 是顶层扩展需显式 import；PCM 无 WAV 头必须 `pcmToWav` 包一层再给 MediaPlayer。
- 新文案 EN/ZH 全部加在 `strings.xml`/`values-zh.xml` 的 `</string>` 段前（无缩进风格）。

**待用户真机验证**：按住录音/上滑取消 → 气泡内联播放 → 顶栏电话拨出/挂断出记录卡 → 设置三态切换（voice_only 隐藏文字、切回恢复）。


## 开源 GitHub + 应用内一键更新（2026-09-28 晚，仓库已完整 + Release 已建，APK 上传重试中）

**成果**
1. **仓库**：`Damonlsy/rikkahub`（fork 自 `rikkahub/rikkahub`，AGPL-3.0 ✓）。main 分支已包含全部代码 + 全部大文件（11 个 jniLibs/.so/idf.utf8/baseline profiles 齐全），HEAD `9c1da29`。PAT：（已从文档移除，见本机上传脚本 apk_upload_direct.ps1 与 git remote URL）（repo scope）。
2. **Release**：`v2.6.0`（release id `398213418`，非 prerelease，`/releases/latest` 可命中）。tag 已修正指向 `9c1da29`（创建时没指定 target_commitish，tag 一度落在默认分支 master=上游代码，已删重建）。
3. **UpdateChecker**（`utils/UpdateChecker.kt`）：`https://api.github.com/repos/Damonlsy/rikkahub/releases/latest`，解析 `tag_name/published_at/body/assets[]`，`removePrefix("v")` 后 SemVer 比较（`2.5.4-beta1 < 2.6.0` ✓），有新版发 `UiState.Success`（含下载列表）否则 `Idle`；`downloadUpdate` 走 DownloadManager。ChatVM 仍受 `updateCheckDisabledUntilEpochMillis` 门控。

**网络经验（关键，下次直接用）**
- 本机系统代理 `127.0.0.1:7897`（Clash Verge Rev，浏览器走它所以网页操作一直正常），**git/curl 默认不走**；已设 `git config --global http.proxy/https.proxy http://127.0.0.1:7897`。
- `github.com` 直连 IP 间歇被墙（20.205.243.166 不通 / 140.82.113.3、114.3 通）；`api.github.com`、`uploads.github.com` 多数时候通。
- **大文件上传会被随机 RST**（无固定阈值，1.5MB~70MB 都可能死）：直连有时能传到 70MB/86MB，代理路线反而总在 1-4MB 死 → APK 走**纯直连循环重试**（`%TEMP%\opencode\apk_upload_direct.ps1`，日志 `apk_upload.log`）。
- **大文件进仓库的正确姿势**：上游 `rikkahub/rikkahub` 本来就有这些文件（fork 网络共享对象存储）→ `git fetch --depth 1 <upstream> master` → `git checkout FETCH_HEAD -- <路径>` → commit → push，**服务器端已有 blob，几乎零流量**，秒过。Contents API 传 8MB+ JSON 会被断开，别试。
- Clash 控制器只在 named pipe（`\\.\pipe\verge-mihomo-...`）+127.0.0.1:9097/secret，TCP 查不到，别浪费时间。
- 小 commit/push 正常（`http.version HTTP/1.1`、`core.compression 0`、`http.postBuffer 100MB` 已配）。

**发版流程（以后每次）**
1. bump `app/build.gradle.kts` 的 `versionCode`/`versionName`（当前 189 / 2.5.4-beta1）。
2. `run_build.ps1` 出 APK（`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`）。
3. GitHub 打 Release：tag 用 `vX.Y.Z` 且**必须指定 `target_commitish=main`**，挂 APK 资产；上传用循环重试直连。
4. 装机版（debug，包名带 `.debug` 后缀）收到更新卡 → DownloadManager 下载 → 覆盖安装。

**阻塞 / 待办**
- APK `RikkaHub-v2.6.0-arm64-v8a-debug.apk`（86.8MB）上传中：后台循环，成功条件 HTTP 201（看 `apk_upload.log`）；Release 目前 0 资产。
- **手机访问 `api.github.com` 100% 丢包**（无稳定代理）→ 真机更新卡出不来，等方案：小克推荐 Cloudflare Worker 代理 GitHub API（免费、国内可达），未拍板。
- PC→github.com 大推送仍偶发 TLS 失败，重试即可。


## 应用内更新上线：Release v2.6.0 + 双源检查 + 镜像下载（2026-09-28 深夜，全链路已验证）

**最终形态**
1. **Release v2.6.0 正式发布**（id `398213418`，draft=False）：资产 `RikkaHub-v2.6.0-arm64-v8a-debug.apk`（81,595,223 字节，versionCode **190** / versionName **2.6.0**）。用户主页分享直链（已测通 206）：
   `https://ghproxy.net/https://github.com/Damonlsy/rikkahub/releases/download/v2.6.0/RikkaHub-v2.6.0-arm64-v8a-debug.apk`
2. **UpdateChecker 双源**（`utils/UpdateChecker.kt`）：`API_URLS = [api.github.com/.../releases/latest, rikkahub-update.rikkahub.workers.dev/releases/latest]`，逐个尝试，全失败才发 `UiState.Error`；下载链接统一改写 `https://github.com/` → `https://ghproxy.net/` 前缀（`DOWNLOAD_MIRROR_PREFIX`）。
3. **Cloudflare Worker 已部署**（备用源）：`https://rikkahub-update.rikkahub.workers.dev/releases/latest`，代码 `deploy/update-proxy-worker.js`（含 `env.GITHUB_TOKEN` secret，防 CF 共享 IP 被 GitHub 限流 403），账号子域 `rikkahub.workers.dev`。部署方式：`C:\...\nodejs\node-v24.21.0-win-x64` 便携 Node + `npx wrangler deploy`（wrangler 已 OAuth 登录，配置在 `%APPDATA%\Roaming\xdg.config\.wrangler`）。

**国内网络结论（实测，别再走弯路）**
- **手机 HTTPS 直连 `api.github.com` 是通的**！之前"手机连不上"是被 ping 误导（GitHub 不回 ICMP）→ 检查更新不需要代理。
- 手机 `github.com` 打不开（转圈后空白）→ 下载必须走镜像。
- **`*.workers.dev` 被 SNI 干扰**（TCP 通、TLS 秒断，真 IP 104.21.x 也一样）→ Worker 国内直连不可达，只能当备用。
- `ghproxy.net` / `ghfast.top` 镜像国内直连可用（206）；PC 直连下载偶发随机 RST，**直连循环重试**能成（新 APK 第 1 轮就过，旧的第 21 轮过）。
- DNS 污染实例：本地解析 workers.dev 返回假 IP `154.92.16.97`；1.1.1.1 DoH（走代理）返回真 IP。

**发版踩坑（下次必看）**
- **删 tag 会把 Release 转成 draft**（或建 Release 时 tag 指错分支），draft 对 `/releases/latest` 返回 404、对外不可见 → 发布后必查 `draft=false`。
- **bump 版本号**：`app/build.gradle.kts` versionCode/versionName（现 190 / 2.6.0）；忘 bump = 测试用户更新后卡永远弹。
- 替换资产：`DELETE /releases/assets/{id}` → 重传（直连循环脚本 `%TEMP%\opencode\apk_upload_direct.ps1`）。
- 手机端用户（你）自己的设备：`updateCheckDisabledUntilEpochMillis=1791206256375`（禁到 2026-10-05）+ 本地已是 2.6.0，双保险永不弹卡。

**其他**
- **用户红线：电脑绝对不能做系统更新**（Windows Update 碰都不碰）；本机只在 `%TEMP%\opencode\nodejs` 放了便携 Node，可随时删。
- git 全局代理已配 `http.proxy=127.0.0.1:7897`（Clash Verge）；小 commit 正常推。
- 已验证：2.6.0 装机（versionCode 190）冷启动无崩溃，前台已还原抖音。

- 参考项目："D:\rikkahub-backup-20260926\orangechat-reference.zip"（OrangeChat 二改项目，用户评价：里面有一个工作流很不错，后续可解包借鉴）。


## 用户九项需求批（2026-09-29，全部完成：assembleDebug 通过 + 单测已跑）

**九项清单与状态**
1. **锁应用找不到软件**（done）：`AndroidManifest.xml` 加 `QUERY_ALL_PACKAGES`。
2. **定时查岗改「AI 自行决定开关」**（done）：`WorkflowStore`（AI 可写开关状态）/ `WorkflowService`（执行时尊重 AI 决定）/ `WorkflowSettingPage`（开关 UI + 提示词）。
3. **拍一拍让 AI 自己选词**（done，09-29）：`PatTools.kt` 描述重写——manner/action/part 由模型根据语气自己挑词传入（词库或自造词），漏传才退回随机抽。
4. **上下文注入手机温度 + 软提示**（done）：`utils/DeviceContext.kt`（电量/天气/最近应用等 <device_context>，经 `DeviceContextTransformer` 挂系统提示词）。
5. **用户语音转文字再发给 AI**（done）：新增 `speech/src/main/java/me/rerere/asr/FileASRTranscriber.kt`（OpenAIRealtime / DashScope / Step / MiMo 分支，PCM/WAV 转换、下混重采样，Volcengine 返回 null 不转写）；`DisplaySetting.voiceInputTranscribe`（默认 true）；`ChatPage.onSendVoiceMessage` 语音 + 文字双 part 发送；`SettingSpeechPage` 新增「语音转文字」开关。
6. **语音条宽度随时长**（done，验证即可）：`VoiceBarBubble.kt` 已有 `width = (72 + 秒*3).coerceIn(72..240)dp`，时长取 metadata 或 MediaMetadataRetriever 探测，无需改动。
7. **AI 发语音：默认文字 / 用户要求 / AI 自决**（done，09-29 完整）：设置「AI 语音回复」四档 off/auto/text_and_voice/voice_only；auto 档触发条件 = 发送时语音消息或触发词（`AIVoiceReply.kt` 的 `AUTO_VOICE_TRIGGERS`）**或**回复文本带 `[语音]`/`[voice]` 标记；`VoiceReplyHintTransformer`（仅 auto 档注入系统提示词）告诉模型可用该标记主动发语音；`ChatVM.appendAssistantMessageParts` 挂语音条时自动把标记从展示文本抹掉，TTS 文本同样剔除。
8. **DeepSeek 新模型不显示**（done，09-29）：`SettingProviderDetailPage.kt` 的 `ModelList` 拉取 /models 后，baseUrl 含 `api.deepseek.com` 时合并 `DEEPSEEK_FALLBACK_MODELS`（`deepseek-flash` / `deepseek-v4-flash` / `deepseek-v4-pro` / `deepseek-v4-flash-vision-exp`，能力取自 `ModelRegistry`），按 modelId 去重排序；拉取异常 printStackTrace 不再吞掉。注意 `ProviderSetting.baseUrl` 只在 OpenAI/Google/Claude 子类上（sealed），要用 when 取值。
9. **非识图模型 OCR 兜底**（无需开发）：上游已有 `OcrTransformer`（`ChatService.inputTransformers` 注册，设置页 `ocrModelId`/`ocrPrompt` 配置识图模型+提示词），直接用即可。

**构建 / 测试（09-29 实跑）**
- `assembleDebug` 通过；`:app:compileDebugKotlin` / `:speech:compileDebugKotlin` 通过。
- `testDebugUnitTest`：6 个失败均与本次改动无关——`workspace` 5 个（Windows 无 /bin/sh、符号链接需特权，环境性）；`ai` 1 个（`StreamTraceReplayTest.replay DeepSeek Chat Completions trace`，ai 模块本次零改动，上游即失败）。
- 新字符串（`values/strings.xml` + `values-zh/strings.xml`）：`setting_speech_ai_voice_auto`、`setting_speech_voice_transcribe_title`、`setting_speech_voice_transcribe_desc`。

**状态**：全部改动仍在工作区未提交；新增文件 `VoiceReplyHintTransformer.kt`、`FileASRTranscriber.kt`、`deploy/`。