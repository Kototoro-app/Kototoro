package org.koitharu.kotatsu.parsers.network

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.json.JSONObject

public interface WebClient {

	/**
	 * Do a GET http request to specific url
	 * @param url
	 */
	public suspend fun httpGet(url: String): Response = httpGet(url.toHttpUrl())

	public suspend fun httpGet(url: String, extraHeaders: Headers?): Response = httpGet(url.toHttpUrl(), extraHeaders)

	/**
	 * Do a GET http request to specific url
	 * @param url
	 */
	public suspend fun httpGet(url: HttpUrl): Response = httpGet(url, null)

	/**
	 * Do a GET http request to specific url
	 * @param url
	 * @param extraHeaders additional HTTP headers for request
	 */
	public suspend fun httpGet(url: HttpUrl, extraHeaders: Headers?): Response

	/**
	 * Do a HEAD http request to specific url
	 * @param url
	 */
	public suspend fun httpHead(url: String): Response = httpHead(url.toHttpUrl())

	/**
	 * Do a HEAD http request to specific url
	 * @param url
	 */
	public suspend fun httpHead(url: HttpUrl): Response

	/**
	 * Do a POST http request to specific url with `multipart/form-data` payload
	 * @param url
	 * @param form payload as key=>value map
	 */
	public suspend fun httpPost(url: String, form: Map<String, String>): Response =
		httpPost(url.toHttpUrl(), form, null)

	/**
	 * Do a POST http request to specific url with `multipart/form-data` payload
	 * @param url
	 * @param form payload as key=>value map
	 */
	public suspend fun httpPost(url: HttpUrl, form: Map<String, String>): Response = httpPost(url, form, null)

	/**
	 * Do a POST http request to specific url with `multipart/form-data` payload
	 * @param url
	 * @param form payload as key=>value map
	 * @param extraHeaders additional HTTP headers for request
	 */
	public suspend fun httpPost(url: HttpUrl, form: Map<String, String>, extraHeaders: Headers?): Response

	/**
	 * Do a POST http request to specific url with `multipart/form-data` payload
	 * @param url
	 * @param payload payload as `key=value` string with `&` separator
	 */
	public suspend fun httpPost(url: String, payload: String): Response = httpPost(url.toHttpUrl(), payload, null)

	/**
	 * Do a POST http request to specific url with `multipart/form-data` payload
	 * @param url
	 * @param payload payload as `key=value` string with `&` separator
	 */
	public suspend fun httpPost(url: HttpUrl, payload: String): Response = httpPost(url, payload, null)

	/**
	 * Do a POST http request to specific url with `multipart/form-data` payload
	 * @param url
	 * @param payload payload as `key=value` string with `&` separator
	 * @param extraHeaders additional HTTP headers for request
	 */
	public suspend fun httpPost(url: HttpUrl, payload: String, extraHeaders: Headers?): Response

	/**
	 * Do a POST http request to specific url with json payload
	 * @param url
	 * @param body
	 */
	public suspend fun httpPost(url: String, body: JSONObject): Response = httpPost(url.toHttpUrl(), body, null)

	/**
	 * Do a POST http request to specific url with json payload
	 * @param url
	 * @param body
	 */
	public suspend fun httpPost(url: HttpUrl, body: JSONObject): Response = httpPost(url, body, null)

	/**
	 * Do a POST http request to specific url with json payload
	 * @param url
	 * @param body
	 * @param extraHeaders additional HTTP headers for request
	 */
	public suspend fun httpPost(url: HttpUrl, body: JSONObject, extraHeaders: Headers?): Response

	/**
	 * Do a GraphQL request to specific url
	 * @param endpoint an url
	 * @param query GraphQL request payload
	 */
	public suspend fun graphQLQuery(endpoint: String, query: String): JSONObject
}
