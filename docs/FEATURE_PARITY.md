# c001apk → c001apk-native 功能对等基线

盘点日期：2026-10-22。此文是实现/验收清单，不是原生版完成声明。

## 1. 冻结来源与证据口径

- Flutter main：`52202fdf9e5847f13cabfa361c47cd0de7404dad`。
- PR #1 分支 `review/floating-nav-ux-audit`：`3f04795ca6fe41284f88ea826c8ab327d4fbe28b`；应用源代码变更末次 `de90ced97ad0ab384e6cd99c76e78c843ed1c558`。PR 增量仅导航/横竖屏及对应测试文档，不改变下述业务 API。
- main 源码入口：[lib](https://github.com/Ktclat/c001apk/tree/52202fdf9e5847f13cabfa361c47cd0de7404dad/apps/flutter/lib)。下文相对路径均相对于 `apps/flutter/lib/`，除另有标注。
- `S`：本次读源码确认实现；`T`：存在对应测试源码，但本次未重新执行；`R`：仓库记录此前执行结果；`D`：本次真机实际验证。本次所有项均无 `D`。
- PR 文档报告 210 passed / 5 skipped、无 analyze error/warning、9 informational；两项在线探针与三项 Windows 登录测试跳过。Linux Flutter 截图、测试视口变形、合成登录状态及请求计数不是 Android 运行/TalkBack/FPS 证据。
- 没有取得此前真机截图原件，不能声称原图视觉回归通过。未访问实际用户账号、未发帖/私信、未上传真实图片、未执行真实在线 API 探针。
- 原生新仓库实现状态由实现团队另列，本文不能将 Flutter 的 `S/T/R` 自动继承为 native 的已实现/已验证。

## 2. 发布阶段与依赖

- P0 基础：许可、模块边界、typed model、导航、错误状态、设备参数/签名、会话隔离、存储、测试夹具。为所有阶段依赖。
- P1 游客阅读闭环：真实动态列表 → 详情/文章/评论 → 图片 → 搜索 → 用户/话题/数码，收藏历史/本地关注/设置。不得以演示数据替代线上接入。
- P2 账号与交互：登录/验证码/多账号 → 远程关注/赞/发布回复 → 我的通知/私信。实际写操作须经过用户授权的测试账号与场景。
- P3 Android 专有与补齐：已安装应用/应用详情/应用更新下载、活动/订阅号/酷图、黑名单备份、原生版本升级、深链全覆盖。
- P4 性能/设备验收：各模块回归，横竖屏、进程死亡、后台恢复、字体、TalkBack、系统手势、真实图片信息流、Macrobenchmark/帧耗时/内存。P4 必须贯穿前述阶段而非最后才补。

## 3. 功能对等矩阵

| ID | 功能与验收行为 | Flutter 来源 / 证据 | 原生实施阶段、依赖与特别边界 |
|---|---|---|---|
| NAV01 | 根导航只有“首页 / 关注 / 我的”；切换保留各列表、滚动位置、嵌套标签；重复点击返回顶部 | pages/main/main_page.dart; pages/home/return_top_controller.dart, S；PR navigation_review_test.dart, T/R | P0；不要另造“应用”第四根标签 |
| NAV02 | Android 首页顶部依次 应用、动态、热门、话题、数码、酷图，默认动态；顶部标签可横滑 | pages/home/home_page.dart, S | P1/P3；应用是 Android 专有顶部标签，不可漏掉酷图 |
| NAV03 | 传统导航与设置中的 opt-in 悬浮导航；图标上文字下，稳定点击区；选中背景果冻，不压缩文字/图标；切页轻微入场，不穿越中间页 | PR components/floating_tab_bar.dart; pages/main/main_page.dart, S/T/R | P0/P4；详见第7节，不盲目抄固定像素 |
| NAV04 | 自动隐藏/恢复、IME隐藏、系统安全区、字体放大、辅助功能、旋转（历史源码含宽屏 rail；当前要求悬浮模式在宽屏仍保持底部 overlay，仅经典模式可用 rail） | 同上及 main.dart, S/T/R；Android真机 NOT_RUN | P0/P4；隐藏时无点击/焦点/语义节点，减少动画时即时切换 |
| NET01 | API签名/头、身份与设备范围、明确错误分类；HTTP200仍检查业务包络 | logic/network/{request,interceptor,api_failure}.dart; utils/token_util.dart, S/T | P0；禁止凭据跨域、无限验证码循环及不确定写操作自动重放 |
| HOME01 | 首页动态分页/刷新/更多/空状态/失败重试/去重；首次启动参数 | pages/home/feed/home_feed_controller.dart; pages/common/common_controller.dart, S | P1；保留 page/firstItem/lastItem语义，不用offset替代 |
| HOME02 | 热榜与酷图推荐由服务端 page/dataList卡片组织；专题轮播/网格/横向滚动卡片 | components/item_card.dart; components/cards/*; pages/carousel/*, S | P1；typed sealed card family，未知卡片可降级，不崩溃 |
| HOME03 | 话题分类、数码分类与子列表 | pages/home/topic/*, S | P1；服务端提供分类/URL，不把示例内容硬编码 |
| FOLLOW01 | 登录关注流：全部/用户/话题/数码/应用过滤与持久化选择 | utils/utils.dart:getFollowUrl/getFollowTitle; pages/main/main_page.dart, S | P2；依赖账号会话 |
| FOLLOW02 | 游客本地关注；登录时可启用“悄悄关注”、长按关注按钮操作；设备本地名单与账号无关 | logic/local_follow/local_follow_store.dart; pages/user/*; pages/local_follow/*, S/T | P1；不要发送远程关注，不随账号切换清空 |
| FOLLOW03 | 多用户公开流合并、新到旧、全局/单用户去重、各用户独立游标、最多4并发、失败可重试 | logic/local_follow/local_follow_pager.dart, S/T | P1；限制空/屏蔽页扫描，部分失败显示警告，不误报全空 |
| FEED01 | 动态详情、HTML内容/链接、长文结构化text/image/shareUrl、封面标题、图片列表 | pages/feed/feed_controller.dart; components/{feed_article_body,html_text}.dart, S/T | P1；解析失败单独显示，不渲染为假成功 |
| FEED02 | 账号详情被身份/验证码拦截时读取官方公开表示；明确账号点赞状态未知；主动验证失败保留可读正文 | logic/network/network_repo.dart:getFeedDetail; pages/feed/*, S/T | P1；仅认证/验证码失败允许回退，不能隐藏限流/风险/升级要求 |
| FEED03 | 评论默认/最新/热度/仅作者，置顶/回复我的补入；楼中楼分页、回复定位；屏蔽用户过滤 | pages/feed/*; pages/feed/reply2reply/*, S | P1；replyList参数见第4节 |
| FEED04 | 动态/评论赞和取消赞、删除本人内容、复制、系统分享、举报网页、屏蔽 | pages/common/common_controller.dart; components/{like_button,cards/feed_card,cards/feed_reply_card}.dart; pages/feed/feed_page.dart, S | P2；成功确认后更新，失败不能假装成功；举报不是自建原生举报接口 |
| FEED05 | 发布动态/应用评论/话题数码关联、图片、表情、仅自己可见；回复及回复并转发；图形验证码续发 | pages/feed/reply/{reply_page,reply_dialog,emoji_panel}.dart; utils/oss_util.dart, S | P2；需上传/会话/验证码；不要声称支持未实现的完整文章/投票创作 |
| SEARCH01 | 搜索输入、保存/删除历史；六类结果 动态/应用/游戏/话题/数码/用户 | pages/search/*; components/cards/search_history_card.dart, S | P1；保留关键字/pageType/pageParam范围检索 |
| SEARCH02 | 动态类型 全部/动态/文章/酷图/评论/评分/回答/问答/投票；排序最新/默认/热门/最新回复/精确 | pages/search/search_result_page.dart; search_result_content.dart, S | P1；这是检索过滤，不代表投票/评分操作实现 |
| USER01 | 用户空间、用户动态、统计/等级/头像、用户搜索、粉丝/关注入口、分享复制屏蔽举报私信 | pages/user/*; components/cards/user_info_card.dart, S | P1/P2；游客与登录关注分支不同 |
| USER02 | 远程关注/取消关注、用户/应用/话题/数码已关注列表 | pages/user/user_controller.dart; pages/ffflist/*, S | P2；并发/账号切换不能更新旧UI |
| TOPIC01 | 话题/数码详情，服务端tabList、默认/最新/热门；关注/取消、复制分享屏蔽、关联发布 | pages/topic/*, S | P1/P2；topic/newTagDetail vs product/detail，动态URL页 |
| OTHER01 | 订阅号文章 精选/广场；酷图主题 精选/热门/最新 | pages/dyh/*; pages/coolpic/*, S | P3；不要用空页面/网页替代标为原生已完成 |
| OTHER02 | 活动标题/图/正文/描述/规则/报名截止/外部报名页，错误/下架可重试 | pages/event/event_page.dart, S/T | P3；活动ID仅数字；外部报名不自动提交 |
| LIST01 | 我/他人动态、粉丝、用户/应用关注、最近浏览（服务端）、赞、回复、回复我的、收藏单及收藏单内容 | pages/ffflist/{ffflist_page,ffflist_content,ffflist_controller}.dart, S | P2/P3；服务器收藏单 != 本地收藏，两个都要盘点 |
| LOCAL01 | 本地收藏及历史列表，倒序、详情跳转、单条删除/清空；历史开关，摘要150字符 | pages/history/*; logic/model/fav_history/*; pages/feed/feed_controller.dart, S | P1；现有源码没有收藏/历史导出导入界面，不伪称现有能力 |
| LOCAL02 | 用户ID黑名单、话题关键词黑名单，新增/删除/清空/JSON导出导入（合并去重） | pages/blacklist/*; utils/storage_util.dart, S | P1/P3；Android导出走SAF；关键词匹配 tags/ttitle/relationRows首项标题 |
| ACCOUNT01 | 账号密码登录、预取requestHash/登录session、图形验证码/人机验证；密码不持久化 | pages/login/login_page.dart; logic/network/network_repo.dart, S/T | P2；不能用测试fixture模拟已登录交付 |
| ACCOUNT02 | 多账号新增/切换/移除、游客模式、失效再登录；原子canonical vault及旧镜像修复 | logic/accounts/account_store.dart; pages/accounts/*, S/T | P2；原生更应隔离敏感凭据，迁移不能直接复制Hive秘密 |
| ACCOUNT03 | sessionGeneration拒绝旧请求/旧cookie/旧验证；profile/checkLoginInfo刷新；挑战不能误清会话 | logic/accounts/account_store.dart; pages/main/main_controller.dart; logic/network/*, S/T | P0/P2；新账号不应短暂露出前账号私信、通知、草稿 |
| CAPTCHA01 | 网易易盾交互验证、严格配置/凭证校验、取消回退、最多一次已拒绝请求重试；空field使用validate Cookie | logic/network/captcha_challenge.dart; pages/webview/captcha_page.dart; request.dart, S/T | P2；保留人机参与，不实现规避风控 |
| COOKIE01 | API cookie域/路径/有效期/安全范围，账号设备绑定，冷启动复用已被服务器接受的验证状态 | logic/network/api_session_store.dart; utils/web_session.dart; interceptor.dart, S/T | P0/P2；公开详情无账号Cookie；GitHub更新无酷安头 |
| ME01 | 我的头像/账号信息/等级、动态关注粉丝、收藏历史入口、通知未读数及刷新 | pages/message/*; components/cards/message_*_card.dart, S | P2；游客也有本地入口，不能整个“我的”强制登录 |
| NOTICE01 | @动态/@评论/赞/好友关注/私信/系统消息；删除通知；私信会话置顶/取消置顶/删除 | pages/noitfication/*, S/T | P2；服务端确认后修改列表/计数，ukey不能用uid代替 |
| CHAT01 | 分页聊天记录、图片URL解析、文本/图片发送；发送中/成功/失败、重试/丢弃、不确定送达提示 | pages/chat/{chat_controller,chat_page,chat_image_uploader}.dart, S/T | P2；发送结果需服务端message记录，不确定不可自动再发 |
| CHAT02 | 从我的动态/本地收藏/输入链接选择“发送动态”；弹窗确认后发送标准URL文本 | pages/chat/chat_feed_picker.dart, S/T | P2；不是移植官方私有卡片协议 |
| MEDIA01 | 九宫格/缩略图/GIF、全屏图片翻页缩放、位置计数、保存当前/全部、分享/复制链接 | components/{network_image,nine_grid_view,imageview}.dart; pages/others/imageview_page.dart; utils/download_util.dart, S | P1/P3；新实现按现代系统最小授权，不盲搬老存储权限 |
| APP01 | 枚举非系统已安装应用：图标/名称/包名/版本/更新时间；排序/刷新，进入酷安应用详情 | pages/home/app/*; Android MainActivity.kt:getInstalledApps, S | P3；QUERY_ALL_PACKAGES策略/隐私需另验，后台线程与图标缓存避免主线程卡顿 |
| APP02 | 应用详情、下载信息/截图、最近回复/最新发布/热度；关注/屏蔽/复制分享/应用评论 | pages/app/*; components/cards/app_info_card.dart, S | P3；下载接口需id/包名/versionCode |
| APP03 | 已安装应用更新检查与列表；302下载地址，交给Android DownloadManager，失败外部浏览器 | pages/home/app/update/*; utils/utils.dart; Android MainActivity.kt, S | P3；不能等同静默安装，不能用本客户端升级代替应用更新 |
| SET01 | 动态取色/固定主题色、浅色深色跟随系统、字体三选项、独立字号、舒适/原版排版 | pages/settings/settings_page.dart; utils/{app_theme,reading_preset,storage_util}.dart, S/T | P1；含一次性typographyV2迁移，随后不覆盖用户选择 |
| SET02 | 悬浮导航开关、悄悄关注、表情、黑名单、历史、外部浏览器、已安装应用更新开关 | 同上, S/T | P1；有存储字段不代表当前设置有可见入口（如部分imageQuality/showSquare字段） |
| SET03 | 缓存大小/清理；保留账号/收藏/历史/设置/会话持久化数据 | utils/cache_util.dart; test/ui/cache_safety_test.dart, S/T | P1；cache不应与database混清 |
| DEVICE01 | 稳定设备配置，协议版本/UA/X-App-Device展示编辑/重置；首次本地ca_标识；不随机拼接机型 | utils/{device_profile,device_util,storage_util,token_util}.dart; pages/settings/params_page.dart, S/T | P0；ca_不是官方数盟ID，不保证接口接受 |
| DEVICE02 | 主动粘贴官方OAID页面内容解析ShuzlmID、定向打开官方包；手填数盟ID/机型系统；空标识保留旧值 | pages/settings/{device_identity_page,manual_device_info_page}.dart; utils/{official_device_identity,manual_device_info}.dart, S/T | P1/P2；不自动读剪贴板、不清空账号数据、不承诺免验证 |
| UPDATE01 | 自身GitHub正式Release检查、语义版本、自动每进程一次/开关/跳过版本、手动检查、版本说明与下载入口 | logic/app_update/*; components/update_*.dart, S/T | P3；原生必须指向native仓库，不继承Flutter APK更新地址；无Release是明确状态 |
| ABOUT01 | 关于/版本/仓库/反馈/完整许可/第三方致谢 | pages/settings/about_page.dart; docs/THIRD_PARTY_NOTICES.md, S | P0/P3；新的维护/反馈入口独立，保留原来源 |
| LINK01 | 内链feed/event/user/apk/game/topic/product/carousel/dyh/用户列表；coolmarket和HTTP(S)处理、外链WebView/浏览器 | utils/utils.dart:onOpenLink; router/app_pages.dart; AndroidManifest.xml, S/T | P0/P3；路由与Manifest声明不完全等价，未知链接有回退，不将整个host contains规则照搬 |
| WEB01 | 内嵌网页、外部打开、网页返回与验证承载、账号cookie同步及清理范围 | pages/webview/*; utils/{local_web_page,web_runtime,web_session}.dart, S/T | P2/P3；不把账号Cookie注入任意网页，不允许任意JS桥提取敏感值 |

## 4. API 与模型契约（从现有源码提取，不是官方服务保证）

通用：HTTPS API 主域 `api.coolapk.com`；首页与一级评论使用 `api2.coolapk.com`；登录 `account.coolapk.com`。默认连接/接收超时各5秒。分页为 `page`（起始1）+可选 `firstItem/lastItem`；刷新重置游标；只有成功页推进。普通列表按实体ID去重，`noMoreDataCard`终止；过滤后空页不能总等同上游耗尽。本地关注每用户单独分页与buffer。列表接口可能返回嵌套 `listCard.entities`，需展开并限制嵌套深度（现有>20报错）。

| 组 | 方法 / 路径 | 必须保留的参数/特殊响应 |
|---|---|---|
| 首页 | GET api2 /v6/main/indexV8 | page,firstLaunch,installTime,firstItem?,lastItem?,ids='' |
| 服务端卡片页 | GET api /v6/page/dataList | url,title,subTitle,page,firstItem?,lastItem?；热榜url=/page?url=V9_HOME_TAB_RANKING；酷图url=/page?url=V11_FIND_COOLPIC；关注url=/page?url=V9_HOME_TAB_FOLLOW，type=circle/topic/product/apk |
| 分类 | GET /v6/page/dataList; GET /v6/product/categoryList | 话题url=V11_VERTICAL_TOPIC,title=话题,page=1；数码分类独立接口 |
| 详情 | GET /v6/feed/detail | id仅数字；仅认证/挑战失败允许 GET https://www.coolapk.com/feed/{id} 公开表示；公开数据userAction=null |
| 评论 | GET api2 /v6/feed/replyList | id,listType,page,firstItem?,lastItem?,discussMode=1,feedType=feed,blockStatus=0,fromFeedAuthor=0或1 |
| 楼中楼 | GET api /v6/feed/replyList | id,listType='',discussMode=0,feedType=feed_reply,blockStatus=0,fromFeedAuthor=0及分页 |
| 用户 | GET /v6/user/space; GET /v6/user/feedList; GET /v6/user/profile | space/profile:uid；feedList:uid,page,cursors,showAnonymous=0,isIncludeTop=1或0,showDoing=0 |
| 搜索 | GET /v6/search | type,feedType,sort,searchValue,isStrict,pageType?,pageParam?,page,cursors,showAnonymous=-1 |
| 话题/数码 | GET /v6/topic/newTagDetail; GET /v6/product/detail | tag / id；data.tabList的pageName/title/url + selectedTab驱动子页；子页走dataList |
| 应用 | GET /v6/apk/detail | id={packageName},installed=1；讨论dataList.url=/page?url=/feed/apkCommentList?id={numericId}，可加sort=dateline_desc或popular |
| 已安装应用更新 | POST /v6/apk/checkUpdate?coolmarket_beta=0 | multipart pkgs=Base64(JSON{packageName:'0,versionCode,0'})；不要自行上传其他安装数据 |
| 应用下载 | GET /v6/apk/download | pn,aid,vc,extra=''；禁自动跳转，302 Location交DownloadManager，不转发登录头给下载域 |
| 订阅号/酷图 | GET /v6/dyhArticle/list; GET /v6/picture/list | dyhId,type=all/square；tag,type=recommend/hot/newest；均含page/cursors |
| 活动 | GET /v6/event/detail | id仅数字；data为Map且title字符串，pic/content/description/notice_rule/time_reg_end/action_url可选 |
| 账号检查 | GET /v6/account/checkLoginInfo; /v6/notification/checkCount | skipCaptcha用于账号检查；只有明确“登录信息有误”令当前账号失效，挑战/异常不得清账号 |
| 登录 | account GET /auth/login?type=mobile; GET /auth/loginByCoolApk; POST /auth/loginByCoolApk | 先提取session/requestHash；POST urlencoded submit=1,randomNumber,requestHash,login,password,captcha,code=''；成功从Set-Cookie提取uid/username/token；密码不存 |
| 赞/取消 | POST /v6/feed/like,unlike,likeReply,unLikeReply | query id,detail=0；urlencoded trace='' |
| 删除/关注 | POST /v6/feed/deleteFeed,deleteReply; /v6/notification/delete; /v6/user/follow,unfollow; /v6/product/changeFollowStatus | 按源调用传id/uid/body；apk follow/unFollow经getFollow走GET；具体大小写勿归一化 |
| 发布 | POST /v6/feed/createFeed | message,type=feed或应用comment,targetType/targetId按调用,pic；把旧status=-1映射publish_status=1，否则0，最终status=1；无文件urlencoded，有文件multipart |
| 回复 | POST /v6/feed/reply?id={id}&type={feed/feed_reply} | message,replyAndForward,pic按调用；无文件urlencoded，有文件multipart |
| 图形验证 | GET /v6/account/captchaImage; POST /v6/account/requestValidate | 图片bytes，时间戳防缓存,w=270,h=113；验证成功后按当前操作续发 |
| 通知 | GET /v6/notification/atMeList,atCommentMeList,feedLikeList,contactsFollowList,list | page,cursors；会话GET /v6/message/list |
| 消息 | GET /v6/message/chat; POST /v6/message/send | chat:ukey,page,cursors；send:query uid,multipart message,message_pic；成功必须包含entityType=message且id非空的记录 |
| 图片消息 | GET /v6/message/showImage | id,type=s；302 Location，不自动附带账号头跳转 |
| 会话操作 | GET /v6/message/deleteChat,addTop,removeTop,delete | ukey及id按操作；requireMessageMutation要求data/count非空非false，不能只看HTTP200 |
| 图片上传 | POST /v6/upload/ossUploadPrepare，然后OSS | 见utils/oss_util.dart与pages/chat/chat_image_uploader.dart；临时上传授权仅用于指定endpoint，不能日志泄露 |
| 用户集合 | GET /v6/user/followList,fansList,apkFollowList,recentHistoryList,likeList,replyList,replyToMeList; /v6/collection/list,itemList | uid/id/showDefault=0按页面；server recentHistory与local history分开 |

DTO建议：将原巨大`Datum`拆成 Feed/Reply/User/Topic/Product/App/Notification/Chat/Collection + UI Card；网络层保留raw discriminator与容错层，业务层统一ID为字符串，不把动态数值/字符串ID强转Int。源 `logic/model/feed/datum.dart` 中大量注释字段不表示功能已实现。核心字段：id/entityId/entityType/entityTemplate；uid/userInfo、message/message_raw_output/message_cover；pic/picArr；dateline/lastupdate；likenum/replynum/commentnum；userAction(like/follow)；tabList/selectedTab；replyRows/topReplyRows/replyMeRows；entities/targetRow/relationRows。文章仅解析text/image/shareUrl，其余类型不能假称原生支持。

错误包络：先校验HTTP2xx、JSON对象、code/status/messageStatus/error与data存在，再解析data。区分transport/http/business/authentication/captcha/rateLimit/riskControl/clientUpgrade/invalidResponse/parsing。UI仅安全本地错误，不显示响应原文、请求头、Cookie、完整异常。未知/不兼容包络不能当空列表成功。

## 5. 账号、设备、持久化：下一阶段必保留不变量

### 5.1 本地数据边界

原有Hive盒为 searchHistory、historyFeed、favFeed、blackList、settings；这些是设备本地，不因切换账号自动清空。FavHistoryItem(typeId=0)字段序号0..7依次 id,uid,username,userAvatar,message,device,dateline,time（本地记录微秒时间；内容时间为字符串秒）。历史写入仅在开关开启且尚无该ID时，收藏显式保存。原生Room可自定表，但不能直接声称兼容Hive二进制。跨包名无自动数据迁移；凭据更不能混入普通JSON导出。若新增迁移流程，单独设计可撤销的非敏感数据导出/导入和冲突策略。

settings中的本地关注 `localFollowUsers` 为 [{uid,name,avatar}]；uid为正数字字符串、去重，新关注置前；`secretFollowEnabled`默认false，独立于账号。黑名单为userBlackList/topicBlackList字符串列表，导入合并去重。

### 5.2 账号账本

`accountVaultV1` 单个canonical记录：accounts[{uid,username,token,avatar,level,exp,nextExp}],activeUid。token仅内部用、密码从不保留。username可能URI编码，展示时安全解码。uid须数字、token非空才是可激活账号；失效保留账号展示记录但清token并转游客。所有操作串行，先写canonical再修复legacy镜像；启动遇旧版无vault则从当前账号迁移；已有vault以activeUid匹配可登录记录修复镜像。

切换/添加/移除当前/转游客/失效：递增sessionGeneration，更新全局isLogin/uid/username/token，清临时SESSID/loginSession，清API session与验证，再写镜像并flush，最后发通知。同账号重复切换无操作；移除非当前账号不重置当前会话。refreshCurrent必须比较generation；profile缺字段不能清原字段；checkLoginInfo返回uid必须等于当前uid。原生建议单一AccountRepository+串行事务，原子发布AccountState，所有网络/分页/上传结果携generation，UI按账号重建私有状态。

### 5.3 API Cookie 隔离

`ApiSessionStore.allows`只允许 HTTPS、port443、api.coolapk.com/api2.coolapk.com。仅保存SESSID，不混存账号Cookie。身份指纹为 SHA256(JSON[xAppDevice,isLogin,uid或空,token或空])；不向日志/测试产物输出实际值。响应保存前捕获context=(generation,identity,revision)，只有仍等于当前context才允许写，保证并发晚响应不能覆盖新状态。

记录 apiSessionV1={identity,value,domain,path,expiresAt}；domain仅coolapk.com或当前精确API host，path必须以/开头、<=256；请求路径必须精确匹配或满足斜杠边界前缀。有效期取服务端Max-Age（优先于Expires）和最多7天中较短者；无服务端期限最多7天。空值/过期清会话；值限制长度1..512及安全字符。clear递增revision并清验证。

apiValidationV1={identity,sessionHash,process,generation,value,expiresAt}；proof只在服务端接受后持久化，最长24小时，绑定当前SESSID摘要、身份；同一进程必须generation一致，冷启动允许仍有效且身份/session一致的记录。设备/账号/token变化使旧会话不可用。验证取消、过期、服务端再次挑战均不可继续当已通过。

每个请求捕获generation和(isLogin,uid,token)，回包及重试后再核对。网易验证一次只开一个，verificationEpoch抑制旧挑战；设备需匹配请求时X-App-Device。只在已确定被验证码拒绝时最多重试一次。传输超时/含混HTTP失败的写操作不自动重试。field为空用validate Cookie；否则按原请求类型加query/form字段。multipart先clone，不能复用已finalize对象。

公开www.coolapk.com/feed/{数字ID}使用签名客户端头但绝不带账号/session Cookie；GitHub更新使用独立客户端；WebView cookie桥需域白名单且切换账号清理。Android新实现应改用安全凭据存储/Keystore保护，与普通DataStore偏好分离，但不要把“换储存库”当作已证明安全。

### 5.4 设备与协议参数

设备profile：manufacturer,brand,model,buildNumber,sdkInt,androidVersion，Android来自Build；版本协议默认16.2.0 / code2604201 / api16（这是冻结源码，不保证服务端未来接受）。X-App-Device编码包含9个分号字段；首次本地生成ca_前缀稳定标识，不每次启动轮换。导入官方内容取ShuzlmID而非凭空生成官方数盟ID。手填profile与标识先完整校验，再一次性应用并更新UA/device编码；空标识保留现有，现有编码损坏应安全失败，不清收藏/账号。

请求头白名单内包含 User-Agent,X-Requested-With,X-Sdk-Int,X-Sdk-Locale,X-App-Id,X-App-Token,X-App-Version,X-App-Code,X-Api-Version,X-App-Device,X-Dark-Mode,X-App-Channel,X-App-Mode,X-App-Supported。签名compatibility移植必须用已有合成token_vectors夹具验证，不用真实用户token测试输出。协议迁移只一次更新version/API默认，保留账号绑定设备、本地数据及用户设置。

### 5.5 下一阶段测试期望

1. AccountRepository：旧数据迁移、原子写中断后恢复、重复账号、游客往返、移除非当前不变代际、当前失效保留可展示账号、不存密码、不泄漏token。
2. 并发：账号A请求在切B后返回不落库/不更新UI；A验证/上传完成不得在B发送；token刷新/设备变化也隔离。
3. Cookie：域/path边界、端口、http拒绝、max-age/expires/7天、删除cookie、乱序响应revision、恶意值、过期/错身份/错session validation；冷启动与同进程代际差异。
4. 请求：HTTP200业务错误、缺data、HTML/无效JSON、限流/风险/升级分类；公开回退仅允许指定错误且无Cookie；取消验证码和一次重试；未知送达不自动重发。
5. Device/settings：稳定首次标识，空值保留，错误编码不破坏；主动粘贴才读剪贴板；协议迁移不动本地数据；字体迁移一次；缓存清理不动数据库。
6. UI：账号切换前后通知/私信/跟随列表及根导航重置正确，返回/取消/重复点击/旋转/后台恢复，不以一帧fixture替代真实验收。

## 6. 全部命名路由及非命名入口

router/app_pages.dart 有26个命名入口：`/`, `/settings`, `/accounts`, `/params`, `/about`, `/copy`, `/feed/:id`, `/event/:id`, `/u/:uid`, `/apk/:packageName`, `/search`, `/searchResult`, `/t/:tag`, `/product/:id`, `/webview`, `/appUpdate`, `/carousel`, `/dyh`, `/coolpic`, `/imageview`, `/login`, `/notification`, `/ffflist`, `/history`, `/blacklist`, `/chat`。

另有非命名入口：本地关注管理、官方设备标识导入/手填、回复编辑页/弹窗、楼中楼页、验证码、发送动态选择器、更新弹窗/字体选择及阅读预设。只迁移路由表会漏这些功能。`/copy`是文本复制阅读辅助，不是内容编辑器。`FFFListType.FAV/HISTORY`在FFFListContent内throw UnimplementedError，但实际本地收藏/历史走HistoryPage，不能直接据此称收藏整体未实现，也不能把该死分支作为原生入口。

## 7. 导航/性能要求（native应重现行为，不照搬Flutter布局实现）

PR目标默认dock268×62dp、内边距5dp、约86×52dp点击区、1dp边线、系统底部safe inset再加12dp；中文字体放大时高度可增66/72dp。以上是冻结版设计参照，不是适配所有Android尺寸的硬规则。点击区至少48×48dp；手势安全区、键盘、底部内容/FAB必须完全可访问。所有图标始终在文字上方，三个标签始终可见；长译文需扩展/降级布局及完整语义，不无限压小字号。

选中背景240ms果冻（横向1→1.04→.985→1，纵向1→.94→1.025→1），不动画outer dock/文字/图标/hit geometry；初始和重复选中不弹。页面入场约160ms、4dp→0，仅incoming内容；不能加载/展示中间tab。减少动画/无障碍模式即时完成或保持可见。滚动只驱动dock局部状态，不能每tick重组整个信息流或触发请求。

已记录Flutter证据：暖启动30次切根标签保留controller/state/offset并0新增请求；animation-only320ms中cached内容0build/paintChild；positive control可测到paint。它不是Android帧率/耗电/内存证明。原生验收至少：release/profile基线、真实图片信息流冷/暖、快速连点/取消切换、横竖屏、大字/显示缩放、手势/三键导航、TalkBack焦点、IME、后台/进程死亡。报告P50/P95/P99 frame time或jank数据与设备/版本/场景，不凭“原生”声称不卡顿。

## 8. 已实现范围与不能冒领的功能

- 有源码实现 ≠ 当前服务端接受 ≠ 真机稳定。所有账号/写操作/下载/权限/系统深链都需现场验证。
- 没有证据支持：完整视频播放器/后台播放、投票投票动作、完整图文文章创作、官方数盟SDK自动注册、跨包名自动迁移账号/收藏、静默安装、云端同步本地收藏/悄悄关注、收藏历史导入导出。这些应标“未发现实现/新增范围”，不要制造空入口。
- 本地缓存不是离线全文库；收藏是摘要与ID，不保证无网能读完整文章。
- 图形/易盾验证码不等于风控永久解除；保留失败/取消状态，不宣传绕过。
- 应用Manifest声明coolmarket/http(s)路径，不代表全部声明已有原生处理（如collection等）。需逐条映射/回退测试。
- 现有源码仍有宽泛明文网络/旧权限/弱类型等技术债，功能对等不要求复制缺陷；必须记录有意安全改进及其对行为的影响。

## 9. 许可与来源移植清单

根LICENSE为AGPL-3.0；README与THIRD_PARTY_NOTICES明确保留历史署名、版权和第三方声明。依此源码重写/移植不得简单改成MIT或去除归属。交付衍生软件时按适用许可证提供相应许可、修改说明及对应源代码获取方式；涉及网络服务的修改版本应核对AGPL相应条款。本文仅记录源码许可事实，不替代发行法律审查。

必须审计并保留实际使用的来源：
- TG-Twilight/c001apk与更早bggRGjQaUbCoE/c001apk-flutter（根AGPL）。
- c001apk-compose接口调研源提交d44c471fb38fa600d28fa33b5dd7fcfd6abb7197（AGPL）。
- daimiaopeng/coolapk-desktop提交3ef2ad4da6d171e118d2b29b0f18d7ffa6da9086：Token V3兼容/查找表、公开详情/验证参考；保留docs/licenses/coolapk-desktop-MIT.txt。查找表第三方来源权利不由MIT保证。
- PiliPlus GPL-3.0：图标配色/设置/账号/更新参考声明，保留实际参考部分的归属与许可，不宣称图形素材可自由取用。
- Noto Sans CJK SC字体OFL-1.1：保留OFL及字体版权，当前fetch脚本固定源提交与blob校验；不能仅复制otf不附许可。
- 酷安官方牛牛表情等素材权利属于相应权利人，并非AGPL自动授权所有素材；保留声明、单独核对分发范围。
- 原生新依赖另生成独立依赖许可清单，不能把Flutter依赖清单当native清单。

来源：[README](https://github.com/Ktclat/c001apk/blob/52202fdf9e5847f13cabfa361c47cd0de7404dad/README.md)、[完整许可](https://github.com/Ktclat/c001apk/blob/52202fdf9e5847f13cabfa361c47cd0de7404dad/LICENSE)、[第三方声明](https://github.com/Ktclat/c001apk/blob/52202fdf9e5847f13cabfa361c47cd0de7404dad/docs/THIRD_PARTY_NOTICES.md)、[PR导航验证记录](https://github.com/Ktclat/c001apk/blob/3f04795ca6fe41284f88ea826c8ab327d4fbe28b/docs/reviews/floating-navigation-verification.md)。

## 10. 功能完成门槛

每个矩阵ID单独维护 native状态：未实现 / 源码实现 / 单元通过 / instrumentation通过 / 真实服务通过 / 真机通过；附commit、测试命令、设备/系统、失败与跳过。某阶段“可构建”或首页有内容不能折算整体对等百分比。mock只能在测试或明确demo中出现，生产无数据时诚实显示加载/错误/暂无。直到全部目标行为与独立复审过关，不能合并或称全功能完成。
