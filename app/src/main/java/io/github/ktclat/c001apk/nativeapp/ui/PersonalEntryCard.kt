// SPDX-License-Identifier: AGPL-3.0-only
// Flutter ffflist_content.dart → common_body.dart → item_card.dart dispatch contract.
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ktclat.c001apk.nativeapp.domain.*
import io.github.ktclat.c001apk.nativeapp.feature.feed.*
import io.github.ktclat.c001apk.nativeapp.feature.media.MediaGrid
import io.github.ktclat.c001apk.nativeapp.local.validUid

/** Presentation only. No account, mutation, local-write or credential dependency.
 * Preserve the personal decoder's primary destination instead of deriving a public fallback.
 * Independently source-owned author/reference/template links use the existing root link policy.
 */
@Composable internal fun PersonalEntryCard(entry: PersonalEntry, navigate: (String) -> Unit,
    openLink: (String) -> Unit, refresh: () -> Unit, modifier: Modifier = Modifier) {
    val item = entry.content
    val open: (() -> Unit)? = when (val destination = entry.destination) {
        is PersonalDestination.Route -> ({ navigate(destination.value) })
        is PersonalDestination.Link -> ({ openLink(destination.value) })
        PersonalDestination.Unsupported -> null
    }
    Column(modifier) {
        entry.likeAuthor?.let { Text("${it}赞了这条内容", Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
        when {
            isEntityCard(item) -> {
                EntityCard(item, navigate, openLink, actionOverride = EntityCardAction(open),
                    titleOverride = entry.label, showSourceTabs = false)
                if (item.kind != "collection" && entry.detail.isNotBlank() && entry.detail != item.message)
                    PagedPostText(entry.detail, item.bodyTruncated, openLink)
            }
            item.kind == "feed" -> {
                val projected = item.toFeedCardModel(System.currentTimeMillis() / 1000)
                // Disable malformed reference targets before the shared row exposes a click.
                val safe = projected.copy(
                    forward = projected.forward?.let { if (numericPersonalId(it.id)) it else it.copy(id = "") },
                    hotReply = projected.hotReply?.let { if (numericPersonalId(it.parentFeedId)) it else it.copy(parentFeedId = "") },
                )
                NativeFeedCard(safe, FeedCardActions(
                    openPost = open, openComments = open,
                    openAuthor = item.userId.takeIf(::validUid)?.let { uid -> ({ navigate("user:$uid") }) },
                    openLink = openLink,
                    openReference = { reference ->
                        val id = if (reference.kind == FeedReferenceKind.HOT_REPLY) reference.parentFeedId else reference.id
                        if (!reference.deleted && numericPersonalId(id)) navigate("feed:$id")
                    },
                ))
                if (item.childrenIncomplete) Text("部分嵌套内容数据不完整或超出显示上限", Modifier.padding(10.dp))
                if (open == null) Text("暂不支持打开此内容", Modifier.padding(10.dp))
            }
            item.kind == "feed_reply" -> {
                FeedReplyRow(item.copy(userId = item.userId.takeIf(::validUid).orEmpty()), navigate, openLink,
                    likeState = when (item.replyInteraction?.likeState) {
                        ExplicitLikeState.LIKED -> FeedLikeState.LIKED
                        ExplicitLikeState.NOT_LIKED -> FeedLikeState.NOT_LIKED
                        else -> FeedLikeState.UNKNOWN
                    }, readOnly = true, openContent = open)
                if (item.children.isNotEmpty() || item.childrenIncomplete)
                    Text("此回复另含尚未支持的嵌套内容", Modifier.padding(10.dp))
                if (open == null) Text("暂不支持打开此内容", Modifier.padding(10.dp))
            }
            item.kind == "card" && item.entityTemplate == "iconLinkGridCard" -> IconLinkGridCard(item, openLink)
            homeCardTemplate(item) != null -> HomeTemplateCard(item, requireNotNull(homeCardTemplate(item)), openLink, refresh)
            else -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    NativeRichText(AnnotatedString(entry.label), style = MaterialTheme.typography.titleMedium)
                    if (entry.detail.isNotBlank()) PagedPostText(entry.detail, item.bodyTruncated, openLink)
                    if (item.imageUrls.isNotEmpty()) MediaGrid(item.imageUrls)
                    if (item.kind == "card" || item.children.isNotEmpty() || item.childrenIncomplete)
                        Text("此卡片的模板或嵌套内容暂不支持")
                    if (open != null) TextButton(onClick = open) { Text("查看内容") }
                    else Text("暂不支持打开此内容")
                }
            }
        }
    }
}

private fun numericPersonalId(value: String) = value.isNotEmpty() && value.all { it in '0'..'9' }
