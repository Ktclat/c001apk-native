# 原生修复独立运行 UI 审阅

**结论：APPROVE——批准以下限定修复增量，不代表整个应用或线上服务全面验收。**

审阅时间：2026-10-27 14:45 UTC。独立检查了冻结源码、实际执行记录、63 个关键原始模拟器截图、窗口/字形边界及实际导出文件字节。没有用测试通过数替代视觉验收。

## 构建与执行绑定

- 提交：`6a5acbf79d9dc4d94ab609034ea35e0478f58609`
- 源码树：`ddf2e0ffee189663afd2ea0d1f8af8a9301c8ce0`
- Debug APK SHA-256：`ea58d71b5614949f326ec84619088442f755f4884609ce62c599ee918b366134`
- 测试 APK SHA-256：`af977b183b51f21faae3ea0fb88b82087267e5be663cbe1332d16c4757e06c58`
- 独立复核冻结文件未变：554 个文件；完整单元测试 **1,454 项 / 215 套件，0 失败、错误、跳过**。Lint 34 个警告、1 个提示、0 个错误，并非零警告。
- 测试前后从模拟器读回的已安装 APK 均与上述文件一致。独立解析 DEX 定义：生产原生命名空间 2,857 类、测试 150 类，交集为零；测试 APK 没有替换生产实现。
- 实际 API 26 软件模拟器上，**28 个不同场景通过；含 2 次窄屏重复运行共 30 次通过，另有 1 次默认竖屏的横屏假设跳过，不计通过**。对应横屏场景已在真实旋转 1 下单独执行通过。

## 批准范围与原图

以下是核验存档内的路径标识，**不是仓库相对超链接**：`runtime/` 对应 `native-emulator-evidence/6a5acbf79d9d-runtime/`；`build/` 对应 `native-integration-evidence/6a5acbf79d9d/`。完整原图与回执保留在核验存档，代表性截图另行提供。

1. **MainActivity（4 个场景）**：游客 My → 登录门槛 → 设置/黑名单 → 返回；主窗口深浅色与恢复；字体、正文排版、字号三类真实弹层的深浅色及重建。主窗口和弹层系统图标均可辨认。
2. **真实 DocumentsUI / SAF（含在上述 4 场景内）**：实际取消后另存唯一文件 `native-qa-users-1793111013823.json`。从观察到的 Downloads 路径读回恰好 12 字节 `["42424242"]`，SHA-256 `ae8405ddd5650611aa1343128757d82bf2fba779a52e6c4632b34256c7ee221b`；不是仅凭“已导出”判断。
3. **个人富卡片（6 个合成数据场景）**：已解码的图片、转发、链接、热评、嵌套回复和已知模板恢复；主内容/转发/作者/回复关联动态目标正确；未知计数不伪造零，不支持内容明确说明；收藏保持账号专属路径。请求账本无意外路径或社交写入。
4. **验证后重试（4 个合成验证场景）**：初次和下一页通用重试使用当前验证结果一次；保留旧行及完全相同的页码/游标；未读数量重试不重复读取资料；取消/明确刷新产生新挑战或作废旧证明。
5. **黑名单（14 个不同场景）**：用户/含特殊字符话题能打开并返回原列表，未隐式解除屏蔽；分类清空、导出、草稿/恢复、并发及旧回调边界通过。其中 11 个场景明确采用假 ActivityResultRegistry / 内存文件 I/O，不能替代上面的真实 SAF 证明。
6. **实际尺寸复验**：真实旋转 1 的 1280×720 横屏已计入黑名单场景；640×1200 窄屏另重复运行 2 个场景。有效字体 2×/2.4× 下关键内容、不可撤销警告和操作可见。

决定性原始截图：

- 已修复的横屏初始警告：`runtime/landscape-first-evidence/blacklist-shortLandscapeLargeFontActionsAndClearDialogRemainReachable-landscape-dark-font240-clear.png`（SHA-256 `2606a92ddcdd121da57be12bf44610036be7cc0d752a90e1802d804fcbdaf26f`） / 滚至完整备份说明：`runtime/landscape-first-evidence/blacklist-shortLandscapeLargeFontActionsAndClearDialogRemainReachable-landscape-dark-font240-clear-tail.png`（SHA-256 `7d437ac3f8aac7d4595dbeaf81cab36baaa2d7f9a0313805b597eb4eb7ac8825`）
- 深色主窗口：`runtime/production-evidence/production-theme-02-dark.png`（SHA-256 `059a6a2efa1415986711614c60e67a1f15b8aaad3b884ca2fb39bd0fd754d3c1`） / 深色活动弹层：`runtime/production-evidence/production-modal-dark-0-active.png`（SHA-256 `b64b1592923e4dc0f9603587a18c0e20a09916e1433c0501a509b9a3853e171a`） / 浅色活动弹层：`runtime/production-evidence/production-modal-light-0-active.png`（SHA-256 `de1d70cee15fc3bb246c480e58d96334c89244bf32a8273dec9e17d7c93a9277`）
- 实际另存位置：`runtime/production-evidence/production-saf-03-create-unique-document.png`（SHA-256 `8535de35b9b5b6f2c5fe7be3c009b49ec3fbf01ae29a8181beed5a0ff8e184e7`） / 导出返回：`runtime/production-evidence/production-saf-04-export-completed.png`（SHA-256 `fb54bb6dbc74545d734c35e11b0eab68fac4ce3ee4437f4dcb611f0b7b704aad`）
- 个人富卡片：`runtime/rich-evidence/rich-myFeedsMainGalleryForwardAuthorAndBack-feed-initial.png`（SHA-256 `08a4b8240be313e3e91c1e2690c5b4d98e3db05587939d8c4eaa596d482cf8c5`） / 明确回复目标：`runtime/rich-evidence/rich-myRepliesAndReceivedRepliesUseExplicitFeedUrlNotReplyId-reply-rich-row.png`（SHA-256 `6b102bf031f3dcf78ecd2952b8b83407cf5a6f859e189babbbce1b513c9f9ccc`）
- 关闭键盘后可见提交：`runtime/graphic-evidence/retry-failedMoreGraphicGenericRetryPreservesRowsAndExactCursor-visible-submit-reachable.png`（SHA-256 `024280d9f5245e4b687cdadfc695d26eddb6198fdfbbcfacfdf9ef83c6d083f5`） / 重试后旧行与新行同时存在：`runtime/graphic-evidence/retry-failedMoreGraphicGenericRetryPreservesRowsAndExactCursor-generic-retry-success.png`（SHA-256 `03d3bf5ec352ae029603e6453fce3307ca22a91039e57ebdaeb7c554b150e804`）
- 640×1200 窄屏警告与操作：`runtime/narrow-evidence/blacklist-largeSystemAndAppFontActionsAndClearDialogKeepEffectiveDensity-portrait-dark-font240-clear.png`（SHA-256 `665f9a8fb241820b30839e4e80fd7548ebe453a0b7240ea2da702872217f27a8`）

此前拒绝的短横屏对话框已实际修复：1280×720、有效字体 2.4× 时，不可撤销警告初始完整可读；正文滚到底部后备份句完整可读；取消和确认均可见。实际正文视口 160 px、行高 98 px，初始 13 个与末尾 7 个字形边界均包含在视口内。没有声称整段正文同时可见。旧构建的残缺字形截图仍保留为拒绝证据。

## 边界与尚未完成项

- 账号内容和验证采用严格拒绝非许可请求的合成夹具，不是实际账号、真实 CAPTCHA 或线上服务验收。未使用真实凭据，未发起线上社交写入。
- 验证提交合格路径为：输入合成 `AB12` → 正常 Back 关闭已确认存在的键盘 → 同一活动对话框与输入保留 → 原图显示无遮挡提交/取消 → **View 级坐标指针事件**提交。不是 OS 全局注入点击证明；键盘展开时按钮仍被遮挡，未宣称此布局已修复。
- 缓存 Compose 点击包装器的成功后重复触发测试，不等于不可变旧 Kotlin 回调测试；后者由另行源码绑定的回归测试证明。
- 字体 2×/2.4× 为夹具有效 LocalDensity；没有宣称修改系统字体设置。真实横屏旋转与窄屏尺寸覆盖不代表完整无障碍验收。
- 尚非完整 Flutter 对齐：点赞者头像/时间、回复关联动态预览仍未建模；个人列表返回仍重取首屏。图库缩放文字在部分图片上对比偏低，缺失嵌套作者时会出现孤立冒号，列为非阻断后续项。
- 不声称实体设备、TalkBack、性能、线上登录会话或首页联网恢复合格。历史启动 ANR、错误尺寸/滞后/遮挡截图与失败测试均保留，不充作成功证据。此次测试结束后恢复原尺寸/旋转设置，已安装 APK 再次校验一致并正常停止模拟器。

## 可复核证据

- 完整构建门禁：`build/final-gate.json`（SHA-256 `cf32d81783ce038979bf58e1080876d3dc0d7c77752acc33291e95b8f6b2c379`）
- 独立逐阶段执行统计：`runtime/independent-reviewer-runtime-status.json`（SHA-256 `38fce436831ff4a2fa0fdbac4200bc736a0592d45094cb1a0ebfed50e302f4d1`）
- 独立已查看原图及 SHA-256 清单：`runtime/independent-reviewer-viewed-originals.json`（SHA-256 `fe14fd10de35276197a5a16f725c43738930e33a1c3c54828be9c2ceba0814c1`）
- 实际 SAF 字节回读：`runtime/saf-export-readback.json`（SHA-256 `2ea7be9f2a93afe80504ab3548c865b129bcea25a30d00212651b48e6e25e9b7`）

本批准只绑定上述确切源码与 APK 及限定范围；后续生产代码或 APK 变化需要重新绑定并验证受影响范围。
