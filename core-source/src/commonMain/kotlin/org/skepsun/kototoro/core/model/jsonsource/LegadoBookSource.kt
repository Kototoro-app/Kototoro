package org.skepsun.kototoro.core.model.jsonsource

import kotlinx.serialization.Serializable

/**
 * Legado book source configuration model
 * Represents a complete book source configuration in Legado format
 */
@Serializable
data class LegadoBookSource(
    val bookSourceName: String,
    val bookSourceUrl: String,
    val bookSourceType: Int = 0,       // 0=文字, 1=音频, 2=图片
    val bookSourceGroup: String? = null,
    val enabled: Boolean = true,
    val searchUrl: String? = null,
    val exploreUrl: String? = null,
    val header: String? = null,        // 请求头配置（JSON格式字符串）
    val loginUrl: String? = null,      // 登录地址
    val loginUi: String? = null,       // 登录UI
    val loginCheckJs: String? = null,   // 登录检测js
    val jsLib: String? = null,         // js库
    val enabledCookieJar: Boolean? = true, // 启用cookieJar
    val concurrentRate: String? = null, // 并发率
    val bookSourceComment: String? = null, // 注释
    val variableComment: String? = null, // 自定义变量说明
    val respondTime: Long = 180000L,    // 响应时间
    val weight: Int = 0,               // 权重
    val bookUrlPattern: String? = null,
    val eventListener: Boolean = false,
    val customButton: Boolean = false,
    val ruleExplore: SearchRule? = null,  // 浏览规则
    val ruleSearch: SearchRule? = null,
    val ruleBookInfo: BookInfoRule? = null,
    val ruleToc: TocRule? = null,
    val ruleContent: ContentRule? = null,
)

/**
 * Search rule for parsing search results
 */
@Serializable
data class SearchRule(
    val bookList: String? = null,      // 列表选择器
    val name: String? = null,          // 名称规则
    val author: String? = null,        // 作者规则
    val coverUrl: String? = null,      // 封面规则
    val bookUrl: String? = null,       // 链接规则
    val intro: String? = null,         // 简介规则
    val lastChapter: String? = null,   // 最新章节规则
    val updateTime: String? = null,    // 更新时间规则
    val kind: String? = null,          // 分类规则
    val wordCount: String? = null,     // 字数规则
    val checkKeyWord: String? = null,  // 校验关键字
    val init: String? = null,          // 初始化脚本
    val webView: Boolean? = false,     // 是否启用 WebView
)

/**
 * Book info rule for parsing book details
 */
@Serializable
data class BookInfoRule(
    val name: String? = null,
    val author: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val kind: String? = null,          // 分类/标签
    val lastChapter: String? = null,
    val updateTime: String? = null,    // 更新时间
    val wordCount: String? = null,     // 字数
    val tocUrl: String? = null,        // 目录页链接规则
    val init: String? = null,          // 初始化脚本
    val canReName: String? = null,
    val downloadUrls: String? = null,
    val webView: Boolean? = false,     // 是否启用 WebView
)

/**
 * Table of contents rule for parsing chapter list
 */
@Serializable
data class TocRule(
    val chapterList: String? = null,    // 章节列表选择器
    val chapterName: String? = null,    // 章节名称规则
    val chapterUrl: String? = null,     // 章节链接规则
    val nextTocUrl: String? = null,     // 下一页目录
    val isVolume: String? = null,       // 卷标识
    val isVip: String? = null,          // VIP 标识
    val isPay: String? = null,          // 付费标识
    val updateTime: String? = null,     // 更新时间
    val preUpdateJs: String? = null,    // 刷新前JS
    val formatJs: String? = null,       // 格式化JS
    val webView: Boolean? = false,      // 是否启用 WebView
)

/**
 * Content rule for parsing chapter content
 */
@Serializable
data class ContentRule(
    val content: String? = null,       // 内容规则
    val subContent: String? = null,    // 副文规则（拼接在正文后面，或获取歌词等）
    val title: String? = null,         // 正文页内标题修正
    val nextContentUrl: String? = null,// 正文分页
    val webJs: String? = null,         // 网页JS
    val sourceRegex: String? = null,   // 资源正则
    val replaceRegex: String? = null,  // 正文替换
    val imageStyle: String? = null,    // 图片样式
    val imageDecode: String? = null,   // 图片bytes二次解密JS
    val payAction: String? = null,     // 支付操作
    val callBackJs: String? = null,    // 内容加载后回调JS
    val webView: String? = null,       // 是否启用 WebView (Legado 规则中此处可能是字符串 "true" 或 JS 脚本)
    val webViewDelayTime: Long? = null, // WebView 延迟时间
)
