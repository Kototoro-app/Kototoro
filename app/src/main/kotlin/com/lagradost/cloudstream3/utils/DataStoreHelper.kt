package com.lagradost.cloudstream3.utils

/**
 * The part of Cloudstream's per-account data store helper plugins use: the account prefix of their keys. Kototoro
 * has no Cloudstream accounts, which is the official app's single default account, number 0.
 */
object DataStoreHelper {
	val currentAccount: String get() = "0"
}
