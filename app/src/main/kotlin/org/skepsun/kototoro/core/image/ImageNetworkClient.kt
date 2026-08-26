package org.skepsun.kototoro.core.image

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Coil 网络图片专用客户的每请求「整次调用」超时。
 *
 * 背景（P2 真机验证）：Coil 的 `OkHttpNetworkFetcherFactory` 共享全局 OkHttp 客户端，
 * 其 callTimeout 是 300s（为大型下载保留）。弱网/VPN 黑洞时，一个封面请求可以长时间
 * 占住 Coil 图片并发槽位，放大发热与卡顿。真机测量正常封面 0.5–2.6s，因此按场景把
 * 图片调用收紧到 [IMAGE_NETWORK_CALL_TIMEOUT_MS]（20s）：超时即释放槽位，交给上层
 * 失败/冷却/重试逻辑处理。
 *
 * 注意：不能在拦截器里改 `chain.call().timeout()`——OkHttp 的调用级 AsyncTimeout 在
 * 调用创建时就已绑定客户端配置，拦截器内修改不会生效（有回归测试锁定这一点，
 * 见 `ImageNetworkClientTest`）。
 */
fun buildImageNetworkClient(
    base: OkHttpClient,
    callTimeoutMillis: Long = IMAGE_NETWORK_CALL_TIMEOUT_MS,
): OkHttpClient = base.newBuilder()
    .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
    .build()

const val IMAGE_NETWORK_CALL_TIMEOUT_MS = 20_000L
