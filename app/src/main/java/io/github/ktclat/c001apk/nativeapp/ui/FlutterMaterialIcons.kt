// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import io.github.ktclat.c001apk.nativeapp.R

/** Exact Flutter 3.24.5 font outlines. vectorResource caches by resource/configuration.
 * Glyphs remain CC-BY-4.0; see assets/licenses/FlutterMaterialIcons-NOTICE.txt.
 */
internal object FlutterMaterialIcons {
    @get:Composable
    val Add: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_add)

    @get:Composable
    val AllInclusive: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_all_inclusive)

    @get:Composable
    val ArrowBack: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_arrow_back)

    @get:Composable
    val Block: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_block)

    @get:Composable
    val Close: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_close)

    @get:Composable
    val Copy: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_copy)

    @get:Composable
    val CopyOutlined: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_copy_outlined)

    @get:Composable
    val DeleteOutline: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_delete_outline)

    @get:Composable
    val EmojiEmotions: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_emoji_emotions)

    @get:Composable
    val ErrorOutline: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_error_outline)

    @get:Composable
    val Feed: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_feed)

    @get:Composable
    val FeedOutlined: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_feed_outlined)

    @get:Composable
    val Home: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_home)

    @get:Composable
    val HomeOutlined: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_home_outlined)

    @get:Composable
    val Image: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_image)

    @get:Composable
    val InfoOutline: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_info_outline)

    @get:Composable
    val Keyboard: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_keyboard)

    @get:Composable
    val KeyboardArrowDown: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_keyboard_arrow_down)

    @get:Composable
    val KeyboardArrowRightOutlined: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_keyboard_arrow_right_outlined)

    @get:Composable
    val LinkRounded: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_link_rounded)

    @get:Composable
    val MessageOutlined: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_message_outlined)

    @get:Composable
    val MoreVert: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_more_vert)

    @get:Composable
    val Person: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_person)

    @get:Composable
    val PersonOutline: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_person_outline)

    @get:Composable
    val PhotoOutlined: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_photo_outlined)

    @get:Composable
    val Refresh: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_refresh)

    @get:Composable
    val Reply: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_reply)

    @get:Composable
    val Search: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_search)

    @get:Composable
    val Send: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_send)

    @get:Composable
    val Smartphone: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_smartphone)

    @get:Composable
    val ThumbUp: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_thumb_up)

    @get:Composable
    val ThumbUpAltOutlined: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_thumb_up_alt_outlined)

    @get:Composable
    val Update: ImageVector
        get() = ImageVector.vectorResource(R.drawable.flutter_update)
}
