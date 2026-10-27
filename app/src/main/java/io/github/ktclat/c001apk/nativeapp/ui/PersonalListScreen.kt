// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Defensive even for restored or synthetic states: an active ID alone is not a login. */
fun personalListAccountId(state: io.github.ktclat.c001apk.nativeapp.account.AccountState): String? =
 state.activeId?.takeIf { id -> !state.recoveryRequired && state.accounts.any { it.id == id && !it.needsLogin } }
fun personalListRoute(list: MyPersonalList) = "mylist:${list.name}"
fun parsePersonalListRoute(route: String?): PersonalListRequest? {
 if(route?.startsWith("mycollection:") == true) return route.substringAfter(':').takeIf { it.matches(Regex("[0-9]+")) }?.let { PersonalListRequest(PersonalListKind.COLLECTION_ITEMS, it) }
 if(route?.startsWith("mylist:") != true) return null
 val kind = PersonalListKind.entries.firstOrNull { it.name == route.substringAfter(':') && it in setOf(PersonalListKind.FEED, PersonalListKind.FOLLOW, PersonalListKind.FAN, PersonalListKind.RECENT, PersonalListKind.COLLECTION, PersonalListKind.LIKE, PersonalListKind.REPLY) } ?: return null
 return PersonalListRequest(kind)
}
/** Key every owned UI state and coroutine scope to account identity + generation. */
@Composable fun PersonalListScreen(repository: PersonalListRepository, request: PersonalListRequest,
 accountId: String?, generation: Long, onBack: () -> Unit, onNavigate: (String) -> Unit, onOpenLink: (String) -> Unit) {
 key(accountId, generation, request, repository) {
  // This route owns its full window surface, including guest/loading/error states and inset areas.
  Surface(Modifier.fillMaxSize().testTag("personal-screen-background"), color = MaterialTheme.colorScheme.background) {
  Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
  if(accountId == null) {
   Column(Modifier.fillMaxSize().padding(16.dp)) {
    TextButton(onClick = onBack) { Text("返回") }; Text("登录后查看${request.kind.title}")
    Button(onClick = { onNavigate("login") }) { Text("登录") }
   }
  } else PersonalListContent(repository, request, accountId, onBack, onNavigate, onOpenLink)
  }
  }
 }
}
private data class PersonalListState(val entries: List<PersonalEntry> = emptyList(), val loading: Boolean = false,
 val loaded: Boolean = false, val next: PageCursor? = null, val failure: ApiResult.Failure? = null, val failedMore: Boolean = false)
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PersonalListContent(repository: PersonalListRepository, request: PersonalListRequest, uid: String,
 onBack: () -> Unit, navigate: (String) -> Unit, openLink: (String) -> Unit) {
 val tabs = when(request.kind) {
  PersonalListKind.FOLLOW -> listOf(PersonalListKind.FOLLOW, PersonalListKind.TOPICS, PersonalListKind.PRODUCTS, PersonalListKind.APPS)
  PersonalListKind.REPLY -> listOf(PersonalListKind.REPLY, PersonalListKind.REPLY_ME)
  else -> listOf(request.kind)
 }
 var selected by remember { mutableIntStateOf(0) }
 Column(Modifier.fillMaxSize()) {
  TopAppBar(title = { Text(request.kind.title) }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }, windowInsets = WindowInsets(0,0,0,0))
  if(tabs.size > 1) TabRow(selectedTabIndex = selected) { tabs.forEachIndexed { index, kind -> Tab(selected = selected == index, onClick = { selected = index }, text = { Text(if(kind == PersonalListKind.FOLLOW) "用户" else kind.title) }) } }
  key(selected) {
   var state by remember { mutableStateOf(PersonalListState()) }
   var alive by remember { mutableStateOf(true) }
   var epoch by remember { mutableLongStateOf(0L) }
   val scope = rememberCoroutineScope()
   DisposableEffect(Unit) { onDispose {
    alive = false; epoch++; state.failure?.personalRead?.cancel()
   } }
   fun load(more: Boolean, retry: PendingPersonalRead? = null) {
    if (!alive || state.loading || (more && state.next == null)) return
    if (retry != null && state.failure?.personalRead !== retry) return
    val old = state
    val cursor = if (more) old.next!! else PageCursor()
    val frozen = request.copy(kind = tabs[selected])
    val attempt = ++epoch
    if (old.failure?.personalRead !== retry) old.failure?.personalRead?.cancel()
    // Retain this owner's prior rows/cursor while refreshing or verifying; never infer the
    // failed request mode from row presence, which can turn a failed refresh into append.
    state = old.copy(loading = true, failure = if (retry != null) old.failure else null)
    scope.launch {
     val result = try { retry?.retryOnce() ?: repository.load(frozen, uid, cursor) }
      catch(e: CancellationException) { if (alive && epoch == attempt) state = old.copy(loading = false); throw e }
      catch(_: Exception) { ApiResult.Failure(FailureKind.NETWORK, "加载失败，请重试") }
     if (!alive || epoch != attempt || !currentCoroutineContext().isActive) {
      (result as? ApiResult.Failure)?.personalRead?.cancel()
      return@launch
     }
     state = when(result) {
      is ApiResult.Failure -> old.copy(loading = false, loaded = true, failure = result, failedMore = more)
      is ApiResult.Success -> {
       val all = if(more) old.entries + result.value.items else result.value.items
       // Contacts intentionally preserve duplicate raw records, matching Flutter's override.
       val seen = mutableSetOf<String>()
       val rows = if(frozen.kind in setOf(PersonalListKind.FOLLOW, PersonalListKind.FAN)) all else all.filter { contentListIdentity(it.content)?.let(seen::add) ?: true }
       PersonalListState(rows.take(2000), loaded = true, next = result.value.nextCursor.takeIf { rows.size < 2000 })
      }
     }
    }
   }
   LaunchedEffect(Unit) { load(false) }
   LazyColumn(Modifier.fillMaxSize().testTag("personal-list"), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { TextButton(onClick = { load(false) }, enabled = !state.loading) { Text("刷新") } }
    itemsIndexed(state.entries) { index, entry ->
     PersonalEntryCard(entry, navigate, openLink, refresh = { load(false) },
      modifier = Modifier.fillMaxWidth().testTag("personal-entry-$index"))
    }
    if(state.loading) item { CircularProgressIndicator(Modifier.testTag("personal-loading")) }
    state.failure?.let { failure -> item {
     Text(failure.message, Modifier.testTag("personal-error"))
     val failedMore = state.failedMore
     failure.personalRead?.takeIf { !state.loading }?.let { pending ->
      PersonalReadVerification(pending, state.loading) { load(failedMore, retry = pending) }
     }
     if(failure.kind == FailureKind.AUTHENTICATION) TextButton(onClick = { navigate("login") }) { Text("重新登录") }
     TextButton(onClick = retryClick@ {
      // Retained callbacks belong to the displayed failure, not a newer request. Both retry
      // controls consume its verified capability; explicit Refresh remains a fresh read.
      if (state.failure !== failure) return@retryClick
      val verified = failure.personalRead?.takeIf { it.state.value == DetailVerificationState.VERIFIED }
      load(failedMore, retry = verified)
     }, enabled = !state.loading) { Text("重试") }
    } }
    if(state.loaded && !state.loading && state.failure == null && state.entries.isEmpty()) item { Text("暂无内容") }
    if(!state.loading && state.failure == null && state.next != null) item { Button(onClick = { load(true) }) { Text("加载更多") } }
    if(state.entries.size >= 2000) item { Text("已达到本次浏览上限，请刷新后继续") }
    item { Spacer(Modifier.height(24.dp)) }
   }
  }
 }
}
