package io.github.ktclat.c001apk.nativeapp.ui

import android.provider.Settings
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeApp(model: BrowseViewModel, container: AppContainer, settings: AppSettings, accounts: AccountRuntime, accountState: AccountState) {
    val scope = rememberCoroutineScope()
    var localError by remember { mutableStateOf<String?>(null) }
    var root by rememberSaveable { mutableIntStateOf(0) }
    var homeTab by rememberSaveable { mutableIntStateOf(1) }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    var routeHistory by rememberSaveable { mutableStateOf(listOf<String>()) }
    val navigate: (String) -> Unit = { next -> route?.let { routeHistory = (routeHistory + it).takeLast(12) }; route = next }
    val goBack: () -> Unit = { route = routeHistory.lastOrNull(); routeHistory = routeHistory.dropLast(1) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val states by model.states.collectAsStateWithLifecycle()
    val localLoad by remember(container) { container.local.loadStates() }.collectAsStateWithLifecycle(LocalLoadState.Loading)
    val holder = rememberSaveableStateHolder()
    val context = LocalContext.current
    val accessibility = rememberSystemAccessibility()
    val reduceMotion = accessibility.reduceMotion
    var dockVisible by remember { mutableStateOf(true) }
    val touchExploration = accessibility.touchExploration
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val scrollObserver = remember(settings.floatingNavigation, touchExploration, threshold) {
        object : NestedScrollConnection {
            var distance = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && settings.floatingNavigation && !touchExploration) {
                    if ((distance > 0) != (consumed.y > 0)) distance = 0f
                    distance += consumed.y
                    if (distance < -threshold) dockVisible = false
                    if (distance > threshold / 2) dockVisible = true
                }
                return Offset.Zero
            }
        }
    }
    val entrance = remember { Animatable(1f) }
    LaunchedEffect(root, homeTab, route, reduceMotion, touchExploration, settings.floatingNavigation) {
        dockVisible = true
        if (reduceMotion) entrance.snapTo(1f)
        else { entrance.snapTo(0f); entrance.animateTo(1f, tween(160)) }
    }
    val tabs = listOf("应用", "动态", "热门", "话题", "数码", "酷图")
    val keys = listOf("apps", "home", "hot", "topics", "products", "pictures")
    val key = route ?: if (root == 1 && accountState.activeId != null && !settings.secretFollow) "following:${settings.followType}" else keys[homeTab]
    val savedKeys = remember { mutableSetOf<String>() }
    LaunchedEffect(routeHistory, route) {
        val retain = keys.toSet() + routeHistory + listOfNotNull(route)
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
    if (localError != null) AlertDialog(onDismissRequest = { localError = null }, title = { Text("本地存储") }, text = { Text(localError!!) }, confirmButton = { TextButton(onClick = { localError = null }) { Text("知道了") } })
    if (route?.startsWith("chat:") == true) {
        val values = route!!.split(":", limit = 3)
        HostedScreen("私信", goBack) { ChatScreen(accounts.socialRepository, accountState.generation, android.net.Uri.decode(values[1]), values[2]) }; return
    }
    when (route) {
        "accounts" -> { HostedScreen("账号", goBack) { AccountManager(accounts.store, { navigate("login") }) }; return }
        "login" -> { HostedScreen("登录", goBack) { PasswordLogin(accounts.loginCoordinator, accounts.loginTransport, goBack) }; return }
        "notifications" -> { HostedScreen("消息", goBack) { NotificationScreen(accounts.socialRepository, accountState.generation, { conversation, uid -> navigate("chat:${android.net.Uri.encode(conversation)}:$uid") }) }; return }
        "compose" -> { HostedScreen("发布动态", goBack) { SocialComposerScreen(accounts.socialRepository, accountState.generation) }; return }

        "settings" -> { SettingsScreen(settings, { updated -> scope.launch { try { container.settings.update { updated } } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { localError = "设置保存失败，请重试" } } }, goBack,
            { navigate("library:${it.name}") }, { navigate("blacklist") }, { navigate("local-follows") }); return }
        "library:FAVORITES", "library:HISTORY" -> { LocalLibraryScreen(container.local, LibraryKind.valueOf(route!!.substringAfter(":")), goBack, { navigate("feed:$it") }); return }
        "local-follows" -> { LocalFollowsScreen(container.local, goBack, { navigate("user:$it") }); return }
        "blacklist" -> { BlacklistScreen(container.local, goBack); return }
    }
    Scaffold(topBar = {
        Column {
            TopAppBar(title = { Text(if (route != null) "浏览" else listOf("c001apk Native", "关注", "我的")[root]) },
                navigationIcon = { if (route != null) IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen }) { Icon(Icons.Outlined.Search, "搜索") }
                    if (root == 0 || route != null) IconButton(onClick = { model.load(key, refresh = true) }) { Icon(Icons.Outlined.Refresh, "刷新") }
                })
            if (searchOpen) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, label = { Text("搜索动态") })
                TextButton(onClick = { if (query.isNotBlank()) { navigate("search:${query.trim()}"); searchOpen = false } }) { Text("搜索") }
            }
            if (root == 0 && route == null) ScrollableTabRow(homeTab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, label -> Tab(homeTab == i, onClick = { homeTab = i }, text = { Text(label) }) }
            }
        }
    }, bottomBar = {
        if (route == null) {
            if (settings.floatingNavigation) Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 12.dp).height((62 * LocalDensity.current.fontScale.coerceAtLeast(1f)).coerceAtMost(116f).dp), contentAlignment = Alignment.Center) {
                if (dockVisible || touchExploration || searchOpen) FloatingNavigation(root, reduceMotion, onSelect = { root = it })
            }
            else NavigationBar {
                val icons = listOf(Icons.Outlined.Home, Icons.Outlined.FavoriteBorder, Icons.Outlined.Person)
                listOf("首页", "关注", "我的").forEachIndexed { index, label -> NavigationBarItem(root == index, { root = index }, icon = { Icon(icons[index], null) }, label = { Text(label) }) }
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).nestedScroll(scrollObserver).graphicsLayer { alpha = entrance.value; translationY = (1f - entrance.value) * 4.dp.toPx() }) {
            when {
                route != null || (root == 0 && homeTab != 0) || (root == 1 && accountState.activeId != null && !settings.secretFollow) -> {
                    LaunchedEffect(key) { model.load(key) }
                    SideEffect { savedKeys.add(key) }
                    holder.SaveableStateProvider(key) {
                        val rawState = states[key] ?: BrowseState(loading = true)
                        val blacklist = (localLoad as? LocalLoadState.Ready)?.value?.blacklist
                        val visibleState = remember(rawState, blacklist, localLoad) { if (blacklist == null) rawState.copy(items = emptyList(), error = if (localLoad == LocalLoadState.Failed) "本地过滤设置无法读取，未展示未过滤内容" else "正在读取本地过滤设置") else rawState.copy(items = filterBlocked(rawState.items, blacklist)) }
                        ContentList(visibleState, key,
                            retry = { model.load(key, refresh = true) }, more = { model.load(key, more = true) },
                            navigate = navigate, favorite = { item -> scope.launch {
                                try { container.local.favorite(item) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (_: Exception) { localError = "收藏保存失败，请重试" }
                            } })
                    }
                }
                root == 0 && homeTab == 0 -> InstalledApplications { navigate("app:$it") }
                root == 1 -> LocalFollowFeedScreen(container.local, accounts.repository, { navigate("feed:$it") }, { navigate("user:$it") }, { navigate("local-follows") })
                else -> holder.SaveableStateProvider("root-my") { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("我的", style = MaterialTheme.typography.headlineMedium)
                    Text(accountState.accounts.firstOrNull { it.id == accountState.activeId }?.name ?: "未登录")
                    Button(onClick = { navigate("accounts") }) { Text("账号管理与登录") }
                    if (accountState.activeId != null) {
                        TextButton(onClick = { navigate("notifications") }) { Text("消息与私信") }
                        TextButton(onClick = { navigate("compose") }) { Text("发布动态") }
                    }
                    Button(onClick = { navigate("settings") }) { Text("设置与阅读偏好") }
                    TextButton(onClick = { navigate("library:FAVORITES") }) { Text("本地收藏") }
                    TextButton(onClick = { navigate("library:HISTORY") }) { Text("浏览历史") }
                    TextButton(onClick = { navigate("local-follows") }) { Text("本地关注") }
                    TextButton(onClick = { navigate("blacklist") }) { Text("黑名单") }
                } }
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
private fun InstalledApplications(open: (String) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    LaunchedEffect(Unit) { apps = installedLauncherApps(context) }
    if (apps == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else LazyColumn {
        item { Text("仅显示系统允许查询的可启动应用，不申请全量应用列表权限。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
        itemsIndexed(apps.orEmpty(), key = { _, app -> app.packageName }) { _, app ->
            ListItem(headlineContent = { Text(app.label) }, supportingContent = { Text(app.packageName) }, modifier = Modifier.clickable { open(app.packageName) })
        }
    }
}

@Composable
private fun ContentList(state: BrowseState, key: String, retry: () -> Unit, more: () -> Unit, navigate: (String) -> Unit, favorite: (ContentItem) -> Unit) {
    val listState = rememberLazyListState()
    val displayItems = remember(state.items) { flattenCards(state.items) }
    LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.error != null) item {
            Card { Column(Modifier.padding(16.dp)) { Text(state.error); TextButton(onClick = retry) { Text("重试") } } }
        }
        if (state.loaded && state.items.isEmpty() && state.error == null && !state.loading) item { Text("暂无内容") }
        itemsIndexed(displayItems, key = { index, item -> "$index:${item.kind}:${item.id}" }) { _, item ->
            ContentCard(item, navigate, favorite)
        }
        if (key.startsWith("feed:") && state.items.isNotEmpty()) item {
            Button(onClick = { navigate("replies:${key.removePrefix("feed:")}") }) { Text("查看评论") }
        }
        if (state.loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else if (state.next != null) item { TextButton(onClick = more, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
    }
}

@Composable
private fun ContentCard(item: ContentItem, navigate: (String) -> Unit, favorite: (ContentItem) -> Unit) {
    val plainMessage = remember(item.message) { android.text.Html.fromHtml(item.message).toString() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.userName.isNotBlank()) Text(item.userName, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(enabled = item.userId.isNotBlank()) { navigate("user:${item.userId}") })
            if (item.title.isNotBlank()) Text(item.title, style = MaterialTheme.typography.titleMedium)
            if (item.message.isNotBlank()) Text(plainMessage, style = MaterialTheme.typography.bodyLarge)
            item.imageUrls.take(3).forEach { url -> AsyncImage(url, "动态图片", Modifier.fillMaxWidth().heightIn(max = 280.dp)) }
            if (item.kind == "feed" && item.id.isNotBlank()) TextButton(onClick = { navigate("feed:${item.id}") }) { Text("查看动态 · ${item.replyCount} 评论") }
            if (item.kind == "topic" && item.title.isNotBlank()) TextButton(onClick = { navigate("topic:${item.title}") }) { Text("查看话题") }
            if (item.kind == "product" && item.id.isNotBlank()) TextButton(onClick = { navigate("product:${item.id}") }) { Text("查看产品") }
            if (item.kind == "apk" && item.id.isNotBlank()) TextButton(onClick = { navigate("app:${item.id}") }) { Text("查看应用") }
            item.tabs.forEach { tab -> TextButton(onClick = { navigate("page:${tab.url}") }) { Text(tab.title) } }
            if (item.kind == "feed" && item.id.isNotBlank()) TextButton(onClick = { favorite(item) }) { Text("收藏到本机") }
        }
    }
}

private fun filterBlocked(items: List<ContentItem>, blacklist: Blacklist): List<ContentItem> = items
    .filterNot { blacklist.blocks(it.userId, it.topicTags, it.topicTitle, it.firstRelationTitle) }
    .map { if (it.children.isEmpty()) it else it.copy(children = filterBlocked(it.children, blacklist)) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostedScreen(title: String, back: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

/** Keep server card structure in domain, but materialize child rows lazily rather than 500 nested Composables. */
private fun flattenCards(items: List<ContentItem>): List<ContentItem> = buildList {
    fun append(nodes: List<ContentItem>) { nodes.forEach { item -> add(item.copy(children = emptyList())); append(item.children) } }
    append(items)
}
