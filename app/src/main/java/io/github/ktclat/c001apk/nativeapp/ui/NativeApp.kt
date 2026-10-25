package io.github.ktclat.c001apk.nativeapp.ui

import android.provider.Settings
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ktclat.c001apk.nativeapp.feature.media.MediaGrid
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.flow.first
import io.github.ktclat.c001apk.nativeapp.domain.ApiResult
import io.github.ktclat.c001apk.nativeapp.domain.FailureKind
import io.github.ktclat.c001apk.nativeapp.account.*
import io.github.ktclat.c001apk.nativeapp.social.*
import io.github.ktclat.c001apk.nativeapp.AppContainer
import io.github.ktclat.c001apk.nativeapp.settings.*
import io.github.ktclat.c001apk.nativeapp.local.*
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Person
import io.github.ktclat.c001apk.nativeapp.domain.ContentItem
import io.github.ktclat.c001apk.nativeapp.platform.*
import io.github.ktclat.c001apk.nativeapp.diagnostics.NativeDiagnosticsScreen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NativeApp(model: BrowseViewModel, container: AppContainer, settings: AppSettings, accounts: AccountRuntime, accountState: AccountState, initialRoute: String? = null, myScrollState: ScrollState = rememberScrollState(), browseListStates: Map<String, LazyListState> = emptyMap()) {
    val scope = rememberCoroutineScope()
    var localError by remember { mutableStateOf<String?>(null) }
    var root by rememberSaveable { mutableIntStateOf(0) }
    var homeTab by rememberSaveable { mutableIntStateOf(1) }
    var route by rememberSaveable { mutableStateOf<String?>(if (accountState.recoveryRequired) "accounts" else initialRoute) }
    var routeHistory by rememberSaveable { mutableStateOf(listOf<String>()) }
    val navigate: (String) -> Unit = { next -> if (next != route) { route?.let { routeHistory = (routeHistory + it).takeLast(12) }; route = next } }
    val goBack: () -> Unit = { route = routeHistory.lastOrNull(); routeHistory = routeHistory.dropLast(1) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val states by model.states.collectAsStateWithLifecycle()
    val localLoad by remember(container) { container.local.loadStates() }.collectAsStateWithLifecycle(LocalLoadState.Loading)
    val holder = rememberSaveableStateHolder()
    SideEffect {
        model.readerPositions.retainRoutes(ReaderOwner(accountState.activeId, accountState.generation),
            routeHistory + listOfNotNull(route))
    }
    val context = LocalContext.current
    val openLink: (String) -> Unit = { raw -> when (val link = parseAppLink(raw)) {
        is AppLink.Internal -> navigate(link.route)
        is AppLink.External -> try { openWebLink(context, link.url, settings.openInBrowser) } catch (_: android.content.ActivityNotFoundException) { localError = "未找到可打开此链接的浏览器" }
        AppLink.Unsupported -> { localError = "此链接暂不支持，未打开外部地址" }
    } }
    NativeUpdatePrompt(container.nativeUpdates) { navigate("native-updates") }
    val accessibility = rememberSystemAccessibility()
    val reduceMotion = accessibility.reduceMotion
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    // Floating mode remains a bottom overlay in every window orientation, including phones in landscape.
    val useRail = route == null && !settings.floatingNavigation && (configuration.screenWidthDp >= 600 || configuration.screenWidthDp > configuration.screenHeightDp)
    val floatingRoot = route == null && settings.floatingNavigation
    val observeDockScroll = shouldObserveFloatingDockScroll(floatingRoot, keyboardOpen, searchOpen)
    val density = LocalDensity.current
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val drawingInsets = WindowInsets.safeDrawing
    // Scaffold already consumes visual insets. Add only any remaining gesture-safe delta, once.
    val dockExtraLeft = with(density) { (WindowInsets.systemGestures.getLeft(density, layoutDirection) - drawingInsets.getLeft(density, layoutDirection)).coerceAtLeast(0).toDp() }
    val dockExtraRight = with(density) { (WindowInsets.systemGestures.getRight(density, layoutDirection) - drawingInsets.getRight(density, layoutDirection)).coerceAtLeast(0).toDp() }
    val dockExtraBottom = with(density) { (maxOf(WindowInsets.mandatorySystemGestures.getBottom(density), WindowInsets.tappableElement.getBottom(density)) - drawingInsets.getBottom(density)).coerceAtLeast(0).toDp() }
    val dockBottomGap = if (configuration.screenHeightDp < 600) 8.dp else 12.dp
    val floatingTail = if (floatingRoot && !keyboardOpen) floatingNavigationHeight() + dockBottomGap + 12.dp + dockExtraBottom else 0.dp
    val hideDistance = with(density) { 28.dp.toPx() }
    val showDistance = with(density) { 20.dp.toPx() }
    val dockTravel = with(density) { (floatingNavigationHeight() + dockBottomGap + dockExtraBottom).toPx() }
    val dockScroll = remember(root, homeTab, route, settings.floatingNavigation, settings.followType, settings.secretFollow,
        accountState.generation, hideDistance, showDistance, dockTravel, reduceMotion, keyboardOpen, searchOpen) {
        FloatingDockScrollState(hideDistance, showDistance, dockTravel, reduceMotion)
    }
    var dockSettle by remember(dockScroll) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(dockScroll) { onDispose { dockSettle?.cancel() } }
    // Read the current root's real scroll boundary; unconsumed nested movement alone
    // cannot establish that a vertical root is at the top.
    val dockAtTop = remember(dockScroll) { object { var read: () -> Boolean = { false } } }
    val dockScrollActive = remember(dockScroll) { mutableStateOf<(() -> Boolean)?>(null) }
    val dockVisible by remember(dockScroll) { derivedStateOf { dockScroll.visible } }
    val dockFullyShown by remember(dockScroll) { derivedStateOf { dockScroll.fullyShown } }
    val recoveryEnabled = shouldOfferFloatingDockRecovery(floatingRoot, keyboardOpen, searchOpen, dockFullyShown)
    val currentRecoveryEnabled by rememberUpdatedState(recoveryEnabled)
    val currentDockScroll by rememberUpdatedState(dockScroll)
    val revealDock: (() -> Boolean)? = if (recoveryEnabled) ({
        // Recheck eligibility if an accessibility service invokes an action after the UI changed.
        if (currentRecoveryEnabled && currentDockScroll === dockScroll) {
            dockSettle?.cancel()
            dockScroll.reset()
            true
        } else false
    }) else null
    val reselectEvents = remember { kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 1) }
    val selectRoot: (Int) -> Unit = { index ->
        dockSettle?.cancel()
        dockScroll.reset()
        if (root == index && index in 0..1) reselectEvents.tryEmit(!reduceMotion)
        root = index
    }
    val settleDock: () -> Unit = {
        if (observeDockScroll && currentDockScroll === dockScroll) {
            val target = dockScroll.endFling()
            if (target != dockScroll.hiddenFraction) {
                dockSettle?.cancel()
                dockSettle = scope.launch {
                    val start = dockScroll.hiddenFraction
                    animate(start, target, animationSpec = tween(
                        durationMillis = (180 * kotlin.math.abs(target - start)).toInt().coerceIn(60, 180),
                        easing = CubicBezierEasing(.215f, .61f, .355f, 1f),
                    )) { value, _ -> dockScroll.settleTo(value) }
                }
            }
        }
    }
    // Accessibility ScrollBy also emits UserInput, but does not necessarily dispatch a fling.
    // Observe the real scroll state's end, not a timer, to avoid leaving clipped tabs at rest.
    LaunchedEffect(dockScroll) {
        snapshotFlow { dockScrollActive.value?.invoke() }.collect { scrolling ->
            if (scrolling == false) settleDock()
        }
    }
    val scrollObserver = remember(dockScroll, observeDockScroll) {
        object : NestedScrollConnection {
            private val enabled get() = observeDockScroll
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (enabled && currentDockScroll === dockScroll) {
                    val userInput = source == NestedScrollSource.UserInput
                    // A zero-consumption edge attempt must not interrupt a settle halfway.
                    if (userInput && consumed.y.isFinite() && consumed.y != 0f) dockSettle?.cancel()
                    dockScroll.consume(consumed.y, userInput)
                    // Once a genuine upward-content gesture reaches the root top, reveal even
                    // if less than 20dp remained. Subsequent top-edge pulls consume zero pixels.
                    if (userInput && consumed.y >= 0f && available.y >= 0f &&
                        (consumed.y > 0f || available.y > 0f) && dockAtTop.read()) {
                        dockSettle?.cancel()
                        dockScroll.reset()
                    }
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (enabled) dockScroll.beginFling(available.x, available.y)
                return androidx.compose.ui.unit.Velocity.Zero
            }
            override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                settleDock()
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    val entrance = remember { Animatable(1f) }
    LaunchedEffect(root, homeTab, route, reduceMotion, settings.floatingNavigation) {
        if (reduceMotion) entrance.snapTo(1f)
        else { entrance.snapTo(0f); entrance.animateTo(1f, tween(160)) }
    }
    val tabs = listOf("应用", "动态", "热门", "话题", "数码", "酷图")
    val keys = listOf("apps", "home", "hot", "topics", "products", "pictures")
    val key = route ?: if (root == 1 && accountState.activeId != null && !settings.secretFollow) "following:${settings.followType}" else keys[homeTab]
    val savedKeys = remember { mutableSetOf<String>() }
    LaunchedEffect(routeHistory, route) {
        val retainedRoutes = routeHistory + listOfNotNull(route)
        val retain = keys.toSet() + retainedRoutes + retainedSourceListKeys(retainedRoutes)
        savedKeys.filterNot { it in retain }.toList().forEach { holder.removeState(it); savedKeys.remove(it) }
    }
    BackHandler(route != null || searchOpen || root != 0) {
        when { searchOpen -> searchOpen = false; route != null -> goBack(); else -> root = 0 }
    }
    val detail = states[key]?.items?.firstOrNull()
    LaunchedEffect(key, detail?.id, settings.recordHistory) {
        if (key.startsWith("feed:") && detail != null) try { container.local.record(detail, settings.recordHistory) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { localError = "无法保存浏览历史，原有数据未重置" }
    }
    if (localError != null) AlertDialog(onDismissRequest = { localError = null }, title = { Text("提示") }, text = { Text(localError!!) }, confirmButton = { TextButton(onClick = { localError = null }) { Text("知道了") } })
    if (route?.startsWith("chat:") == true) {
        val values = route!!.split(":", limit = 3)
        HostedScreen("私信", goBack) { ChatScreen(accounts.socialRepository, accountState.generation, android.net.Uri.decode(values[1]), values[2], favorites = {
            when (val saved = container.local.loadStates().first { it != LocalLoadState.Loading }) {
                is LocalLoadState.Ready -> ApiResult.Success(saved.value.favorites.map { FeedShareItem(it.id, it.summary) })
                else -> ApiResult.Failure(FailureKind.INVALID_RESPONSE, "本地收藏无法读取，原有数据未被覆盖")
            }
        }) }; return
    }
    if (route?.startsWith("mylist:") == true || route?.startsWith("mycollection:") == true) {
        val request = parsePersonalListRoute(route)
        val authenticatedId = personalListAccountId(accountState)
        val personalLists = remember(accounts.repository, authenticatedId, accountState.generation) {
            val expectedId = authenticatedId
            val expectedGeneration = accountState.generation
            accounts.repository.personalLists {
                val current = accounts.store.state.value
                expectedId != null && personalListAccountId(current) == expectedId && current.generation == expectedGeneration
            }
        }
        if (request == null) HostedScreen("我的内容", goBack) { Notice("我的内容", "此列表暂不支持") }
        else PersonalListScreen(personalLists, request, authenticatedId, accountState.generation, goBack, navigate, openLink)
        return
    }
    if (route?.startsWith("notifications:") == true) {
        val initialCategory = NoticeCategory.entries.firstOrNull { it.name == route!!.substringAfter(':') }
        HostedScreen("消息", goBack) {
            if (initialCategory == null) Notice("消息分类", "此消息分类暂不支持")
            else if (accountState.activeId == null) Column(Modifier.padding(24.dp)) {
                Text("登录后查看消息")
                Button(onClick = { navigate("login") }) { Text("登录") }
            } else NotificationScreen(accounts.socialRepository, accountState.generation,
                onConversation = { conversation, uid -> navigate("chat:${android.net.Uri.encode(conversation)}:$uid") },
                initialCategory = initialCategory)
        }
        return
    }
    replyDraftForRoute(route)?.let { draft ->
        HostedScreen("回复", goBack) {
            if (accountState.activeId == null) Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text("登录后回复")
                Button(onClick = { navigate("login") }) { Text("登录") }
            } else SocialComposerScreen(accounts.socialRepository, accountState.generation, draft, showTitle = false, onSent = {
                routeHistory.lastOrNull()?.let { previous ->
                    if (previous.startsWith("feed:")) model.invalidateReplies(previous.substringAfter(':'))
                    model.load(previous, refresh = true)
                }; goBack()
            })
        }; return
    }
    when (route) {
        "native-about" -> {
            SideEffect { savedKeys.add("native-about") }
            holder.SaveableStateProvider("native-about") {
                NativeAboutScreen(goBack, { navigate("native-diagnostics") }, { navigate("native-updates") }, { navigate("native-bundled-notices") })
            }; return
        }
        "native-bundled-notices" -> {
            SideEffect { savedKeys.add("native-bundled-notices") }
            holder.SaveableStateProvider("native-bundled-notices") { NativeBundledNoticesScreen(goBack) }; return
        }
        "native-updates" -> { NativeUpdateScreen(goBack, container.nativeUpdates, onOpenDiagnostics = { navigate("native-diagnostics") }); return }
        "native-diagnostics" -> { NativeDiagnosticsScreen(settings.floatingNavigation, accessibility, goBack, accounts.repository.homeDiagnostics); return }
        "app-updates" -> { InstalledAppUpdatesScreen(container.appUpdates, goBack) { navigate("app:$it") }; return }
        "accounts" -> { HostedScreen("账号", goBack) { AccountManager(accounts.store, { navigate("login") }) }; return }
        "login" -> { HostedScreen("登录", goBack) { PasswordLogin(accounts.loginCoordinator, accounts.loginTransport, goBack) }; return }
        "notifications" -> { HostedScreen("消息", goBack) { NotificationScreen(accounts.socialRepository, accountState.generation, { conversation, uid -> navigate("chat:${android.net.Uri.encode(conversation)}:$uid") }) }; return }
        "compose" -> { HostedScreen("发布动态", goBack) { SocialComposerScreen(accounts.socialRepository, accountState.generation, showTitle = false, onSent = { model.load("home", refresh = true); goBack() }) }; return }

        "settings" -> {
            SideEffect { savedKeys.add("settings") }
            holder.SaveableStateProvider("settings") { SettingsScreen(settings, { updated -> scope.launch { try { container.settings.update { updated } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { localError = "设置保存失败，请重试" } } }, goBack,
            { navigate("library:${it.name}") }, { navigate("blacklist") }, { navigate("local-follows") }, onNavigate = navigate,
            isLoggedIn = accountState.activeId != null, accountCount = accountState.accounts.size, nativeUpdates = container.nativeUpdates,
            onSettingsEdit = { edit -> scope.launch {
                try { container.settings.update(edit) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { localError = "设置保存失败，请重试" }
            } },
            onFollowSelection = { type -> scope.launch {
                try { container.settings.selectFollow(type) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { localError = "关注分类保存失败，请重试" }
            } }) }; return }
        "library:FAVORITES", "library:HISTORY" -> { LocalLibraryScreen(container.local, LibraryKind.valueOf(route!!.substringAfter(":")), goBack, { navigate("feed:$it") }); return }
        "local-follows" -> { LocalFollowsScreen(container.local, goBack, { navigate("user:$it") }); return }
        "blacklist" -> { BlacklistScreen(container.local, goBack); return }
    }
    sourceListRoute(route)?.let { linked ->
        HostedScreen(linked.title, goBack) {
            SideEffect { savedKeys.add(requireNotNull(route)) }
            holder.SaveableStateProvider(requireNotNull(route)) {
            SourceListTabs(linked, refresh = { model.load(it, refresh = true) }) { linkedKey ->
                val blacklist = (localLoad as? LocalLoadState.Ready)?.value?.blacklist
                if (blacklist == null) Notice("本地过滤设置", if (localLoad == LocalLoadState.Failed) "无法读取过滤规则，原有数据未被覆盖" else "正在读取过滤规则")
                else {
                    LaunchedEffect(linkedKey) { model.load(linkedKey) }
                    DisposableEffect(model, linkedKey) { onDispose { model.cancelLinkedRead(linkedKey) } }
                    SideEffect { savedKeys.add(linkedKey) }
                    val raw = states[linkedKey] ?: BrowseState(loading = true)
                    val visible = remember(raw, blacklist) { raw.copy(items = filterBlocked(raw.items, blacklist)) }
                    holder.SaveableStateProvider(linkedKey) {
                        ContentList(visible, linkedKey, accounts, accountState, retry = { model.load(linkedKey, refresh = !raw.moreError, more = raw.moreError) },
                            more = { model.load(linkedKey, more = true) }, navigate = navigate, openLink = openLink,
                            favorite = { item -> scope.launch { try { container.local.favorite(item) }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { localError = "收藏保存失败，请重试" } } })
                    }
                }
            }
            }
        }
        return
    }
    if (route?.startsWith("feed:") == true) {
        val sourceMenu = remember(accountState.activeId, accountState.generation, route) { DetailSourceMenuState() }
        DisposableEffect(sourceMenu) { onDispose { sourceMenu.dispose() } }
        HostedScreen("动态", goBack, actions = { sourceMenu.control?.let { DetailSourceMenu(it) } }) {
            val blacklist = (localLoad as? LocalLoadState.Ready)?.value?.blacklist
            if (blacklist == null) Notice("本地过滤设置", if (localLoad == LocalLoadState.Failed) "无法读取过滤规则，原有数据未被覆盖" else "正在读取过滤规则")
            else FeedDetailScreen(route!!.substringAfter(':'), model, accounts, accountState, blacklist, navigate, openLink,
                sourceMenu = sourceMenu) { item ->
                scope.launch { try { container.local.favorite(item) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { localError = "收藏保存失败，请重试" } }
            }
        }
        return
    }
    val safeTopDp = drawingInsets.getTop(density) / density.density
    val safeBottomDp = drawingInsets.getBottom(density) / density.density
    val headerEstimate = safeTopDp + if (root == 0 && route == null) {
        maxOf(48f, with(density) { 20.sp.toDp().value } + 25f)
    } else maxOf(64f, with(density) { MaterialTheme.typography.titleLarge.lineHeight.toDp().value })
    var measuredHeaderHeight by remember(root, route == null, configuration.screenWidthDp, density.fontScale, safeTopDp, searchOpen) {
        mutableFloatStateOf(headerEstimate)
    }
    val actionLabel = when {
        root == 2 -> "私信与信息"
        root == 1 && (accountState.activeId == null || settings.secretFollow) -> "管理本地关注（0）"
        else -> "重试"
    }
    val actionWidthPx = (with(density) { configuration.screenWidthDp.dp.toPx() } -
        drawingInsets.getLeft(density, layoutDirection) - drawingInsets.getRight(density, layoutDirection) -
        with(density) { 48.dp.toPx() }).toInt().coerceAtLeast(1)
    val actionText = rememberTextMeasurer().measure(actionLabel, style = MaterialTheme.typography.labelLarge,
        constraints = Constraints(maxWidth = actionWidthPx))
    val minimumActionDp = maxOf(48f, actionText.size.height / density.density + 16f)
    val scrollRootHeader = floatingRoot && rootHeaderMustScroll(
        windowHeightDp = configuration.screenHeightDp.toFloat(), fixedHeaderHeightDp = measuredHeaderHeight,
        safeBottomDp = safeBottomDp + dockExtraBottom.value,
        dockHeightDp = if (keyboardOpen) 0f else floatingNavigationHeight().value,
        dockGapDp = if (keyboardOpen) 0f else dockBottomGap.value, actionHeightDp = minimumActionDp,
    )
    val rootHeader: @Composable () -> Unit = {
        Column(Modifier.testTag(if (scrollRootHeader) "root-scrollable-header" else "root-fixed-header")
            .onSizeChanged { size ->
                // The same header consumes top insets only in Scaffold's topBar. Inside content,
                // Scaffold has consumed them already. Store one comparable full-height budget.
                measuredHeaderHeight = size.height / density.density + if (scrollRootHeader) safeTopDp else 0f
            }) {
            if (root == 0 && route == null) {
                // Source Home owns one app-bar row: tabs and search, not a second app title.
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)), verticalAlignment = Alignment.CenterVertically) {
                        HomeCategoryTabs(tabs, homeTab, { i ->
                            if (homeTab == i) reselectEvents.tryEmit(!reduceMotion) else homeTab = i
                        }, Modifier.weight(1f))
                        if (homeTab == 1) IconButton(
                            onClick = { navigate(if (accountState.activeId == null) "login" else "compose") },
                            modifier = Modifier.size(48.dp).testTag("home-publish")) {
                            Icon(FlutterMaterialIcons.Add, "发布动态")
                        }
                        IconButton(onClick = { searchOpen = !searchOpen }, modifier = Modifier.floatingDockRecovery(revealDock)) { Icon(FlutterMaterialIcons.Search, "搜索") }
                    }
                }
            } else {
            TopAppBar(title = {
                if (route == null && root == 1 && accountState.activeId != null) FollowContentMenu(settings) { type ->
                    scope.launch { try { container.settings.selectFollow(type) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { localError = "关注分类保存失败，请重试" } }
                } else Text(if (route?.startsWith("event:") == true) "活动详情" else if (route != null) "浏览" else listOf("c001apk Native", "本地关注", "我的")[root])
            },
                navigationIcon = { if (route != null) IconButton(onClick = goBack) { Icon(FlutterMaterialIcons.ArrowBack, "返回") } },
                actions = {
                    if (root == 2 && route == null) {
                        IconButton(onClick = { navigate("accounts") }) { Icon(ProfileIcons.SwitchAccountOutlined, "账号管理") }
                        IconButton(onClick = { navigate("settings") }, modifier = Modifier.floatingDockRecovery(revealDock)) { Icon(ProfileIcons.SettingsOutlined, "设置") }
                    } else IconButton(onClick = { searchOpen = !searchOpen }, modifier = Modifier.floatingDockRecovery(revealDock)) { Icon(FlutterMaterialIcons.Search, "搜索") }
                    if (root == 0 || route != null) IconButton(onClick = { model.load(key, refresh = true) }) { Icon(FlutterMaterialIcons.Refresh, "刷新") }
                })
            }
            if (searchOpen) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, label = { Text("搜索动态") })
                TextButton(onClick = { if (query.isNotBlank()) { navigate("search:${query.trim()}"); searchOpen = false } }) { Text("搜索") }
            }

        }
    }
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = {
        if (!scrollRootHeader) rootHeader()
    }, bottomBar = {
        if (route == null && !settings.floatingNavigation && !keyboardOpen && !useRail) NavigationBar {
            val icons = (0..2).map { rootNavigationIcon(it, it == root) }
            // Gesture bounds exclude NavigationBar's platform insets, unlike an outer detector.
            Row(Modifier.fillMaxWidth().testTag("standard-navigation").navigationTabDrag(root, 3, onSelect = selectRoot)) {
                listOf("首页", "关注", "我的").forEachIndexed { index, label -> NavigationBarItem(root == index, { selectRoot(index) }, icon = { Icon(icons[index], null) }, label = { Text(label) }) }
            }
        }
    }) { padding ->
        CompositionLocalProvider(LocalRootReselect provides reselectEvents, LocalFloatingBottomSpace provides floatingTail, LocalFloatingDockReveal provides revealDock,
            LocalScrollableRootHeader provides if (scrollRootHeader) rootHeader else null) {
        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
        if (useRail && !keyboardOpen) NavigationRail(Modifier.width(if (LocalDensity.current.fontScale > 1.3f) 96.dp else 80.dp).verticalScroll(rememberScrollState()).testTag("navigation-rail")) {
            val icons = (0..2).map { rootNavigationIcon(it, it == root) }
            listOf("首页", "关注", "我的").forEachIndexed { index, label -> NavigationRailItem(root == index, { selectRoot(index) }, icon = { Icon(icons[index], null) }, label = { Text(label) }) }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
        Box(Modifier.fillMaxSize().nestedScroll(scrollObserver).graphicsLayer { alpha = entrance.value; translationY = (1f - entrance.value) * 4.dp.toPx() }) {
            when {
                route != null || (root == 0 && homeTab != 0) || (root == 1 && accountState.activeId != null && !settings.secretFollow) -> {
                    LaunchedEffect(key) { model.load(key) }
                    DisposableEffect(model, key) { onDispose { model.cancelLinkedRead(key) } }
                    SideEffect { savedKeys.add(key) }
                    holder.SaveableStateProvider(key) {
                        val rawState = states[key] ?: BrowseState(loading = true)
                        val blacklist = (localLoad as? LocalLoadState.Ready)?.value?.blacklist
                        val visibleState = remember(rawState, blacklist, localLoad) { if (blacklist == null) rawState.copy(items = emptyList(), error = if (localLoad == LocalLoadState.Failed) "本地过滤设置无法读取，未展示未过滤内容" else "正在读取本地过滤设置") else rawState.copy(items = filterBlocked(rawState.items, blacklist)) }
                        CompositionLocalProvider(LocalPreviewGeometryStore provides model.previewGeometry) {
                        val rootListState = browseListStates[key] ?: rememberLazyListState()
                        SideEffect {
                            dockAtTop.read = { !rootListState.canScrollBackward }
                            dockScrollActive.value = { rootListState.isScrollInProgress }
                        }
                        ContentList(visibleState, key, accounts, accountState,
                            listState = rootListState,
                            localUserRepository = container.local,
                            retry = { model.load(key, refresh = true) }, more = { model.load(key, more = true) },
                            navigate = navigate, openLink = openLink, favorite = { item -> scope.launch {
                                try { container.local.favorite(item) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { localError = "收藏保存失败，请重试" }
                            } })
                        }
                    }
                }
                root == 0 && homeTab == 0 -> holder.SaveableStateProvider("apps") {
                    val rootListState = browseListStates["apps"] ?: rememberLazyListState()
                    SideEffect {
                        dockAtTop.read = { !rootListState.canScrollBackward }
                        dockScrollActive.value = { rootListState.isScrollInProgress }
                    }
                    InstalledAppsScreen(listState = rootListState, showUpdateEntry = settings.checkAppUpdates,
                        onOpenUpdates = { navigate("app-updates") }) { navigate("app:$it") }
                }
                root == 1 -> holder.SaveableStateProvider("root-local-follow") {
                    val rootListState = browseListStates["root-local-follow"] ?: rememberLazyListState()
                    SideEffect {
                        dockAtTop.read = { !rootListState.canScrollBackward }
                        dockScrollActive.value = { rootListState.isScrollInProgress }
                    }
                    LocalFollowFeedScreen(container.local, accounts.repository, { navigate("feed:$it") }, { navigate("user:$it") }, { navigate("local-follows") }, listState = rootListState)
                }
                else -> holder.SaveableStateProvider("root-my") {
                    SideEffect {
                        dockAtTop.read = { myScrollState.value == 0 }
                        dockScrollActive.value = { myScrollState.isScrollInProgress }
                    }
                    OwnProfileDashboard(accounts, accountState, myScrollState, floatingTail, onNavigate = navigate,
                        onPersonalList = { navigate(personalListRoute(it)) })
                }
            }
        }
        if (shouldShowFloatingDock(floatingRoot, keyboardOpen, searchOpen, dockVisible)) {
            // Clip against the full content viewport, not the original dock rectangle:
            // the bar must travel through its bottom gap before leaving the screen.
            Box(Modifier.matchParentSize().clipToBounds()) {
                BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .absolutePadding(left = dockExtraLeft, right = dockExtraRight, top = 12.dp, bottom = dockBottomGap + dockExtraBottom), contentAlignment = Alignment.Center) {
                    val sideGap = if (maxWidth >= 186.dp) 16.dp else 8.dp
                    FloatingNavigation(root, reduceMotion, onSelect = selectRoot, enabled = dockFullyShown,
                        modifier = Modifier.padding(horizontal = sideGap).offset {
                            IntOffset(0, (dockScroll.hiddenFraction * dockScroll.travelExtent).roundToInt())
                        }.graphicsLayer { alpha = 1f - dockScroll.hiddenFraction }.then(if (dockFullyShown) Modifier else Modifier.clearAndSetSemantics { testTag = "floating-navigation" }))
                }
            }
        }
        }
        }
        }
    }
}

@Composable
private fun Notice(title: String, message: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp)); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ContentList(state: BrowseState, key: String, accounts: AccountRuntime, accountState: AccountState, retry: () -> Unit, more: () -> Unit, navigate: (String) -> Unit, openLink: (String) -> Unit, favorite: (ContentItem) -> Unit, listState: LazyListState = rememberLazyListState(), localUserRepository: LocalRepository? = null) {
    ObserveRootReselect(listState)
    val rootHeader = LocalScrollableRootHeader.current
    val displayItems = remember(state.items) { state.items.distinctBy { "${it.kind}:${it.id}:${it.title}" } }
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("content-list").floatingDockRecovery(LocalFloatingDockReveal.current), state = listState, contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 10.dp + LocalFloatingBottomSpace.current), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rootHeader?.let { header -> item(key = "root-header") { header() } }
        if (localUserRepository != null && key.startsWith("user:") && validUid(key.substringAfter(':'))) item(key = "local-user-follow") {
            LocalUserFollowControl(key.substringAfter(':'), localUserRepository, accountState.activeId != null) { navigate("local-follows") }
        }
        if (key.startsWith("replies:")) item { TextButton(onClick = { navigate("compose-reply:feed:${key.removePrefix("replies:")}") }) { Text("回复动态") } }
        if (state.error != null) item {
            Card { Column(Modifier.padding(16.dp)) { Text(state.error); TextButton(onClick = retry) { Text("重试") } } }
        }
        if (state.loaded && state.items.isEmpty() && state.error == null && !state.loading) item { Text("暂无内容") }
        itemsIndexed(displayItems, key = { index, item -> if (item.id.isNotBlank()) "${item.kind}:${item.id}:${item.title}" else "anonymous:$index:${item.kind}" }) { _, item ->
            ContentCard(item, navigate, openLink, favorite, accounts, accountState, retry, isDetail = key == "feed:${item.id}")
        }
        if (key.startsWith("feed:") && state.items.isNotEmpty()) item {
            Button(onClick = { navigate("replies:${key.removePrefix("feed:")}") }) { Text("查看评论") }
        }
        if (state.loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else if (state.next != null) item { TextButton(onClick = more, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentCard(item: ContentItem, navigate: (String) -> Unit, openLink: (String) -> Unit, favorite: (ContentItem) -> Unit, accounts: AccountRuntime, accountState: AccountState, onChanged: () -> Unit, isDetail: Boolean) {
    if (item.kind == "event" && item.event != null) { EventDetailCard(item, openLink); return }
    if (item.kind == "feed") {
        InteractiveFeedCard(item, accounts, accountState, navigate, openLink, favorite, onChanged, detail = isDetail)
        return
    }
    if (item.kind == "feed_reply") { InteractiveFeedReplyRow(item, accounts, accountState, navigate, openLink, onChanged); return }
    if (item.entityTemplate == "iconLinkGridCard") { IconLinkGridCard(item, openLink); return }
    homeCardTemplate(item)?.let { template -> HomeTemplateCard(item, template, openLink, onChanged); return }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.children.isNotEmpty() || item.childrenIncomplete || !item.entityTemplate.isNullOrBlank()) Text("此卡片的模板或嵌套内容暂不支持")
            if (item.userName.isNotBlank()) Text(item.userName, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(enabled = item.userId.isNotBlank()) { navigate("user:${item.userId}") })
            if (item.title.isNotBlank()) Text(item.title, style = LocalReadingStyles.current.title)
            if (item.message.isNotBlank()) PagedPostText(item.message, item.bodyTruncated, openLink)
            if (item.imageUrls.isNotEmpty()) MediaGrid(item.imageUrls)
            item.targetUrl?.takeIf { it.isNotBlank() }?.let { url -> TextButton(onClick = { openLink(url) }) { Text("打开内容链接") } }
            if (item.kind == "feed" && item.id.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isDetail) TextButton(onClick = { navigate("feed:${item.id}") }) { Text("查看动态 · ${item.replyCount} 评论") }
                else TextButton(onClick = { navigate("compose-reply:feed:${item.id}") }) { Text("回复动态") }
                TextButton(onClick = { favorite(item) }) { Text("收藏到本机") }
            }
            if (item.kind == "feed_reply" && item.id.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { navigate("compose-reply:reply:${item.id}") }) { Text("回复评论") }
                TextButton(onClick = { navigate("thread:${item.id}") }) { Text("查看楼中楼") }
            }
            if (item.kind == "topic" && item.title.isNotBlank()) TextButton(onClick = { navigate("topic:${item.title}") }) { Text("查看话题") }
            if (item.kind == "product" && item.id.isNotBlank()) TextButton(onClick = { navigate("product:${item.id}") }) { Text("查看产品") }
            if (item.kind == "apk" && item.id.isNotBlank()) TextButton(onClick = { navigate("app:${item.id}") }) { Text("查看应用") }
            item.tabs.forEach { tab -> TextButton(onClick = { navigate("page:${tab.url}") }) { Text(tab.title) } }
        }
    }
}

internal fun filterBlocked(items: List<ContentItem>, blacklist: Blacklist): List<ContentItem> = items
    .filterNot { blacklist.blocks(it.userId, it.topicTags, it.topicTitle, it.firstRelationTitle) }
    .map { item -> item.copy(children = filterBlocked(item.children, blacklist), replies = filterBlocked(item.replies, blacklist),
        feed = item.feed?.let { feed -> feed.copy(
            forward = feed.forward?.takeUnless { blacklist.blocks(it.authorId, emptyList(), "", "") },
            hotReply = feed.hotReply?.takeUnless { blacklist.blocks(it.authorId, emptyList(), "", "") },
        ) }) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostedScreen(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = back) { Icon(FlutterMaterialIcons.ArrowBack, "返回") } }, actions = actions) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

/** Explicit source target kind; a comment id must never be posted as a feed id. */
internal fun replyDraftForRoute(route: String?): ComposerDraft? {
    val parts = route?.split(":", limit = 3) ?: return null
    if (parts.size != 3 || parts[0] != "compose-reply" || parts[1] !in setOf("feed", "reply") || parts[2].isBlank() || parts[2].any { it !in '0'..'9' }) return null
    return ComposerDraft("", replyId = parts[2], replyToReply = parts[1] == "reply")
}
