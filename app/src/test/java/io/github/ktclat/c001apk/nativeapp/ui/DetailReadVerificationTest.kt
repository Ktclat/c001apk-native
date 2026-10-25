// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.ktclat.c001apk.nativeapp.domain.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DetailReadVerificationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var images = 0
    private var validations = 0
    private var retries = 0
    private fun pending(): PendingDetailRead = PendingDetailRead(ApiChallenge.Graphic("synthetic-operation"), { true },
        image = {
            images++
            val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
            val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
            ApiResult.Success(output.toByteArray())
        }, verify = { answer -> assertEquals("a1B2", answer); validations++; ApiResult.Success(Unit) },
        retry = { throw AssertionError("UI must delegate retry to route owner") })
    @Test fun graphicDialogSubmitsOnlyTypedSyntheticAnswerThenRequiresSeparateReadGesture() {
        val pending = pending()
        compose.setContent { MaterialTheme { DetailReadVerification(pending, false) { retries++ } } }
        assertEquals(0, images); assertEquals(0, validations); assertEquals(0, retries)
        compose.onNodeWithTag("detail-human-verify").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("待输入的图形验证码").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("四位验证码").performTextInput("a1B2")
        compose.onNodeWithText("提交验证").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("detail-verified-retry").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, images); assertEquals(1, validations); assertEquals(0, retries)
        compose.onNodeWithTag("detail-verified-retry").performClick(); assertEquals(1, retries)
    }
    @Test fun cancellingVisibleGraphicDialogDoesNotValidateOrRead() {
        val pending = pending()
        compose.setContent { MaterialTheme { DetailReadVerification(pending, false) { retries++ } } }
        compose.onNodeWithTag("detail-human-verify").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("待输入的图形验证码").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("取消").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertEquals(DetailVerificationState.CLOSED, pending.state.value); assertEquals(0, validations); assertEquals(0, retries)
    }
}
