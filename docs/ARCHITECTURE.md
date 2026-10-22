# c001apk-native 原生迁移架构决策

研究日期：2026-10-22。本文是实施建议，不表示已实现、构建通过或达到 Flutter 功能等价。

## 1. 结论

采用 Kotlin、Jetpack Compose Material 3、单 Activity、ViewModel + StateFlow、单向数据流和 Repository。第一阶段只建一个 app 模块，按 feature、data、model、designsystem、platform 包隔离。等代码量和独立测试需要出现，再抽 core:model、core:data、core:designsystem 和真正独立的 feature 模块。不要为了“原生架构”先复制几十个模块、全量 UseCase 或 api/impl 空接口。

从 Android 官方建议借鉴状态/数据分层，从 Now in Android 借鉴可替换数据源、测试和性能基线；不直接照搬它的新闻同步策略、多模块规模、Firebase 或当前混合预发布依赖。[官方架构建议](https://developer.android.com/topic/architecture/recommendations)；[NIA 架构](https://github.com/android/nowinandroid/blob/main/docs/ArchitectureLearningJourney.md)；[NIA 模块化](https://github.com/android/nowinandroid/blob/main/docs/ModularizationLearningJourney.md)。

## 2. 核对过的参考项目与许可

- Flutter 产品基准：Ktclat/c001apk main 52202fdf9e5847f13cabfa361c47cd0de7404dad；导航的期望行为还包括未合并 PR #1 的图上文下、轻果冻、轻页面过渡与无障碍修复。未合并不代表已验收，不合并旧 PR 来推进新项目。[项目说明](https://github.com/Ktclat/c001apk/blob/main/README.md)。
- 同系原生 Views 项目 bggRGjQaUbCoE/c001apk 真实存在，现为 archived；根许可证文件名是 LICENSE.md，直接确认 AGPL-3.0。可参考功能和响应格式，不能认为其旧接口今天仍可用。[原生源码](https://github.com/bggRGjQaUbCoE/c001apk)；[许可证](https://github.com/bggRGjQaUbCoE/c001apk/blob/main/LICENSE.md)。
- 同系 Compose 项目 bggRGjQaUbCoE/c001apk-compose 也存在，2024-09-19 归档，AGPL-3.0。其 catalog 有 Hilt、Room、Retrofit、Coil、DataStore；构建脚本仍有旧 compiler 配置、广泛资源排除、内部 AGP API，不能整包复制。[catalog](https://github.com/bggRGjQaUbCoE/c001apk-compose/blob/main/gradle/libs.versions.toml)；[构建脚本](https://github.com/bggRGjQaUbCoE/c001apk-compose/blob/main/app/build.gradle.kts)；[许可证](https://github.com/bggRGjQaUbCoE/c001apk-compose/blob/main/LICENSE)。
- 新仓库已有 AGPL-3.0 LICENSE；Flutter 同为 AGPL-3.0；NIA 是 Apache-2.0。复用文件需记录来源、保留版权和对应许可，不能用“重写成 Kotlin”消除原代码许可，也不能删除第三方 NOTICE。[Flutter LICENSE](https://github.com/Ktclat/c001apk/blob/main/LICENSE)；[NIA LICENSE](https://github.com/android/nowinandroid/blob/main/LICENSE)。表情、字体、商标和媒体另行核对，不等于代码许可。

## 3. 可重复构建基线

建议固定 AGP 8.13.2、Gradle 8.13、Kotlin 与 Compose compiler 插件均 2.3.10，Java/Kotlin 输出 target 17。JDK 17 是 AGP 文档最低要求；已有 JDK 21 可用于 Gradle 运行，但仍需实际构建确认。compileSdk/targetSdk 36，不使用动态“latest”。AGP 8.13.2 的 R8 支持 Kotlin 2.3，KGP 2.3.10 的官方兼容范围覆盖上述 Gradle/AGP。[AGP 兼容性](https://developer.android.com/build/releases/agp-8-13-0-release-notes)；[KGP 兼容性](https://kotlinlang.org/docs/gradle-configure-project.html)；[Compose compiler 插件](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)。

用户已明确取消旧 Android 兼容要求，原生项目采用 minSdk 26；不再要求 Android 5.x 支持。Flutter 3.24.5 原先默认 minSdk 21，仅作为历史对照。[Flutter 固定版本定义](https://github.com/flutter/flutter/blob/3.24.5/packages/flutter_tools/gradle/src/main/groovy/flutter.groovy)。

初始可复现基线采用 Compose BOM 2024.12.01、Activity 1.9.3、Lifecycle 2.8.7、Navigation Compose 2.8.5、DataStore 1.1.1；按需使用 Paging 3.3.5、Room 2.6.1、Coil 2.7.0、OkHttp 4.12.0、Retrofit 2.11.0。实际实现使用 Coroutines 1.10.2、kotlinx.serialization JSON 1.9.0。这是保守兼容候选，不是最新依赖声明；实际解析后的完整传递依赖、manifest merger、API 26+ 运行和 lint 仍是构建门禁。不要用 overrideLibrary 或关闭 AAR metadata 检查硬过。

已直接读取 Google Maven 的 BOM POM 与 AAR manifest：BOM 2024.12.01 对应 UI/Foundation 1.7.6、Material3 1.3.1；UI Android 1.7.6 minSdk 21、Lifecycle runtime Android 2.8.7 为 19、Activity 1.9.3 为 19、Navigation Compose 2.8.5 为 21、DataStore preferences Android 1.1.1 为 19、Room runtime 2.6.1 为 14、Paging runtime 3.3.5 为 19。这里只核实了这些直接库，并非完整传递图。[Google Maven BOM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2024.12.01/compose-bom-2024.12.01.pom)。

初步研究中考虑过 BOM 2025.09.01，但已撤回：Compose 1.9 把最低 API 提升到 23；Room 2.8 也有同样变更，照搬 NIA 当前 catalog 会暗中改变设备支持范围。[Compose 发布说明](https://developer.android.com/jetpack/androidx/releases/compose-ui)；[Room 发布说明](https://developer.android.com/jetpack/androidx/releases/room)。

## 4. 状态、导航、注入

- Screen 只接受 UiState 和事件回调，Route 负责生命周期感知收集 ViewModel。使用不可变状态；IO/JSON/数据库不在主线程。UseCase 只用于跨仓库业务组合，简单读取不套无意义一层。
- 初期构造器注入 + AppContainer，测试传 FakeRepository。账户、数据库、后台任务形成依赖图后再统一 Hilt，不为 DI 先阻塞可运行第一版。
- Navigation Compose 2.8.x 的类型安全路由，只传业务 ID，不传整份动态、Cookie 或大型 JSON。主页/关注/我的各自保存返回栈、列表位置；切换不经过中间页、不重复刷新。详情返回恢复原列表；进程重建只恢复非敏感必要参数。[Android 导航](https://developer.android.com/guide/navigation)。
- 用户指定的悬浮导航是产品要求：图标上、文字下；果冻仅选中背景轻微形变，不能缩放点击区域、文字或整栏；支持关闭动画；隐藏时移除点击/焦点/语义。保留标准导航回退。安全区按 WindowInsets 动态计算，不按截图像素硬编码。

## 5. 数据与协议

- 将 Flutter 大型 NetworkRepo 拆成 FeedRepository、SearchRepository、AccountRepository、SocialRepository、MessageRepository、LocalLibraryRepository、SettingsRepository。接口由真实业务范围形成，不逐 endpoint 制造类。
- Retrofit/OkHttp + Kotlin serialization；DTO 与领域模型分开。Flutter Datum 大量 dynamic 字段说明 id、计数、时间可能混合类型，转换器应显式接受已知格式并拒绝异常，不用全局 Any? 掩盖问题。entityType/entityTemplate 映射 sealed FeedCard，未知卡片提供安全降级。
- 可移植的是 endpoint 契约、非敏感字段名、脱敏响应样本、业务错误分类及行为测试；不是直接移植全局 Dio/GetX/Hive 单例。HTTP 200 不等于业务成功，应区分认证、验证、限流、升级、解析、传输错误。用户提示不得回显 URL、响应 body 或异常堆栈。
- 列表使用 PagingSource + stable item keys + contentType；刷新、追加、重试与空状态分开。先做网络分页，不为所有临时推荐流强制 Room RemoteMediator；仅需离线保留的数据落库。[Paging](https://developer.android.com/topic/libraries/architecture/paging/v3-overview)。
- Room 管收藏、历史、搜索历史、黑名单、本地关注和草稿；DataStore 管偏好。数据库迁移带 schema 和迁移测试，不用 destructive fallback 破坏用户数据。

## 6. 账户与安全边界

当前 Flutter 已有 account_store、api_session_store、api_failure 的分离，其 session generation/revision、域名路径范围和迟到响应防护应保留为原生验收契约。切换账号应原子更新 session generation，取消旧请求、隔离分页/缓存/数据库用户数据、拒绝旧响应覆盖新会话；退出清理秘密但不误删用户明确保留的本地资料。

凭据与普通 DataStore 分开，采用 Android Keystore 支持的加密私有存储并从备份排除；不记录密码、Cookie、token 或完整个人信息。安全存储必须有损坏/密钥失效后重新登录路径。[Android 安全指南](https://developer.android.com/privacy-and-security/security-tips)。

认证依照服务允许的流程。验证挑战只显示可操作状态，不自动绕过、伪造官方身份或宣称生成的标识已被服务端信任。调试不能带真实账号偷偷执行点赞、关注、发布、删除、聊天等写操作。网络测试使用脱敏 fixtures/MockWebServer，线上验收另列权限与证据。

新 applicationId 与 Flutter 并存，不宣称自动读取旧应用沙箱。后期可定义带版本的用户主动导出/导入格式，默认只迁移非敏感收藏、设置等；登录状态重新建立。

## 7. 平台与媒体

图片用 Coil 按显示尺寸解码、限制缓存、稳定占位，详情支持缩放/保存/分享；保存走 MediaStore，选图走 Photo Picker/系统选择器。先核对 Flutter 实际播放功能再加入 Media3，避免把未实现的视频播放当作必需额外范围。[Media3](https://developer.android.com/media/media3)。

HTML/文章优先安全富文本解析；需要 WebView 时限制来源、协议、重定向和 JS bridge，不全局忽略 SSL。应用列表通过 PackageManager 后台读取并缓存，权限/包可见性拒绝时有解释和回退，不将 installed-app 数据自动上传。后台任务仅用于已有需要且可恢复的同步/下载；不无条件常驻、轮询或要求通知权限。

## 8. 阶段与验收定义

1. 基座：能安装启动的原生 APK、主题/字号、三根导航、设计组件、游客数据源、CI。演示数据明确标注，不称联网已通。
2. 读取闭环：真实游客首页/分页、详情/评论、搜索、用户/话题/应用/文章、图片与深链，保留错误、空状态、重试。
3. 本地闭环：设置、收藏/历史/本地关注、黑名单、更新检查，数据库迁移与进程重建测试。
4. 账户闭环：登录/切换/退出/失效/验证状态，隔离与秘密存储测试，再经许可做真实账号只读验收。
5. 交互闭环：点赞/收藏/关注、回复/发布/上传、通知/消息/聊天以及清单中的其他功能；每项对应完整异常路径和线上验收状态。
6. 对齐与性能：对照 Flutter 功能矩阵逐项关闭差距，做 Android 5.x 至目标 API、字体200%、深浅色、横竖屏/分屏、手势/三键、TalkBack、输入法与返回键测试；独立 Product Design 复审。

每项至少区分：未实现、已实现未测、离线/模拟测试通过、设备测试通过、线上验证通过。截图测试不能证明真实触摸/无障碍/流畅度，编译绿色不能证明功能等价。性能以 release/profileable 构建的 Macrobenchmark/JankStats/Perfetto、冷启动、连续滚动、反复切 Tab 和内存峰值为证据，不以“换成原生”推断变快。基线记录测试设备、系统、刷新率、构建 SHA、样本与指标，再比较。

旧 PR 的独立复审门禁保留；新项目也不得未经验收合并或宣布整体完成。
