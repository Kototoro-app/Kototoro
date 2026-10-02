package org.skepsun.kototoro.parsers

/**
 * Optional interface for parsers that support in-app username/password login.
 * Host apps can detect and use this to provide credential input fields.
 */
public interface ContentParserCredentialsAuthProvider {

    /**
     * Perform authorization using provided username and password.
     * Returns true if login succeeded and authorization cookies were set.
     */
    public suspend fun login(username: String, password: String): Boolean
}