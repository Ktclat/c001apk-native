// SPDX-License-Identifier: AGPL-3.0-only
package io.github.ktclat.c001apk.nativeapp.data

import io.github.ktclat.c001apk.nativeapp.domain.*
import org.junit.Assert.*
import org.junit.Test

class NotificationTargetFailureTest {
    private fun failure(body: String, path: String = "v6/feed/detail"): ApiResult.Failure = try {
        ApiDecoder.envelope(body, path); throw AssertionError("Expected failure")
    } catch (error: ApiProblem) { error.failure }
    @Test fun exactFlutterFeedNotFoundContractHasFixedUnavailableText() {
        val result = failure("""{"code":404,"message":"动态不存在"}""")
        assertEquals(FailureKind.BUSINESS,result.kind); assertEquals("该动态不存在或暂不可见",result.message)
    }
    @Test fun otherEndpointsCodesStatusesOrUnknownTextKeepSanitizedFailure() {
        for (path in listOf("v6/user/space","v6/feed/detailExtra","v6/feed/replyList","v6/apk/detail"))
            assertEquals("服务器未能完成请求，请稍后重试",failure("""{"code":404,"message":"动态不存在"}""",path).message)
        for (body in listOf("""{"code":403,"message":"动态不存在"}""", """{"status":-2,"code":404,"message":"动态不存在"}""", """{"code":404,"message":"secret-server-text"}""", """{"code":404,"message":"动态不存在","messageStatus":"err_other"}"""))
            assertEquals("服务器未能完成请求，请稍后重试",failure(body).message)
    }
    @Test fun securityAndVerificationClassificationsRemainAuthoritative() {
        for ((field,kind) in listOf("\"messageStatus\":\"err_request_captcha\"" to FailureKind.CAPTCHA,
            "\"messageStatus\":\"err_request_need_upgrade_new_version\"" to FailureKind.CLIENT_UPGRADE,
            "\"status\":-10" to FailureKind.RISK_CONTROL)) {
            val result = failure("""{"code":404,"message":"动态不存在",$field}""")
            assertEquals(kind,result.kind); assertNotEquals("该动态不存在或暂不可见",result.message)
        }
        assertEquals(FailureKind.AUTHENTICATION,failure("""{"code":401,"message":"动态不存在"}""").kind)
        assertEquals(FailureKind.RATE_LIMIT,failure("""{"code":429,"message":"动态不存在"}""").kind)
    }
}
