package org.skepsun.kototoro.ios

import org.skepsun.kototoro.core.source.SourceErrorCode
import org.skepsun.kototoro.core.source.SourceProtocolException
import org.skepsun.kototoro.core.source.SourceRemoteException

/** A site that answered with a browser challenge; [origin] is null when the host could not tell which site. */
data class IosChallenge(val origin: String?)

/** Where to verify a source in the in-app browser, and the User-Agent its requests carry (null: keep Safari's). */
data class IosBrowserSession(val url: String, val userAgent: String?)

/**
 * Source failures as Swift shows them. The wire protocol deliberately carries codes, not library messages, so the
 * readable text comes from the code; Swift passes the Kotlin exception from `NSError.userInfo["KotlinException"]`.
 */
object IosSourceErrors {
    fun challenge(error: Throwable): IosChallenge? = remote(error)
        ?.takeIf { it.error.code == SourceErrorCode.CHALLENGE_REQUIRED }
        ?.let { IosChallenge(it.error.url) }

    /** Readable Chinese text for a source failure, or null when it is not a source protocol error. */
    fun message(error: Throwable): String? {
        if (error is SourceProtocolException) return localized("来源运行时返回了无法识别的结果")
        val remote = remote(error) ?: return null
        return when (remote.error.code) {
            SourceErrorCode.CHALLENGE_REQUIRED -> localized("该站点需要通过 Cloudflare 等浏览器验证")
            SourceErrorCode.SOURCE_UNAVAILABLE -> localized("来源不可用，可能已被移除或停用")
            SourceErrorCode.UNSUPPORTED_OPERATION -> localized("此来源不支持该操作")
            SourceErrorCode.INVALID_ARGUMENT -> localized("请求参数无效，请刷新后重试")
            SourceErrorCode.INVALID_REQUEST, SourceErrorCode.UNSUPPORTED_VERSION -> localized("来源运行时版本不匹配")
            SourceErrorCode.RUNTIME_FAILURE -> localized("来源请求失败，可能是网络问题或站点已变更")
        }
    }

    private fun remote(error: Throwable): SourceRemoteException? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < 8) {
            if (current is SourceRemoteException) return current
            current = current.cause
        }
        return null
    }
}
