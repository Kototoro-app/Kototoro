package org.skepsun.kototoro.core.jsonsource

import android.util.Log

/**
 * Logger for JSON source operations.
 * 
 * Provides structured logging for JSON source import, validation, and management operations.
 * Uses different log levels (DEBUG, INFO, ERROR) to categorize messages by severity.
 */
object JsonSourceLogger {
    
    private const val TAG = "JsonSource"
    
    /**
     * Log the start of a JSON import operation.
     * 
     * @param sourceType The type of source being imported (LEGADO or TVBOX)
     * @param contentLength The length of the JSON content being imported
     */
    fun logImportStart(sourceType: String, contentLength: Int) {
        Log.i(TAG, "Starting import of $sourceType sources (content length: $contentLength)")
    }
    
    /**
     * Log successful completion of a JSON import operation.
     * 
     * @param sourceType The type of source that was imported
     * @param count The number of sources successfully imported
     * @param durationMs The time taken to import in milliseconds
     */
    fun logImportSuccess(sourceType: String, count: Int, durationMs: Long) {
        Log.i(TAG, "Successfully imported $count $sourceType source(s) in ${durationMs}ms")
    }
    
    /**
     * Log a JSON import failure.
     * 
     * @param sourceType The type of source that failed to import
     * @param error The error that occurred
     */
    fun logImportError(sourceType: String, error: Throwable) {
        Log.e(TAG, "Failed to import $sourceType sources", error)
        
        // Log additional context based on error type
        when (error) {
            is JsonSourceError.InvalidJsonFormat -> {
                Log.e(TAG, "  JSON format error at position ${error.position}: ${error.message}")
            }
            is JsonSourceError.MissingRequiredField -> {
                Log.e(TAG, "  Missing required field: ${error.fieldName}")
            }
            is JsonSourceError.DatabaseError -> {
                Log.e(TAG, "  Database operation failed: ${error.operation}")
            }
            is kotlinx.serialization.SerializationException -> {
                Log.e(TAG, "  Serialization error: ${error.message}")
            }
        }
    }
    
    /**
     * Log a source validation failure.
     * 
     * @param sourceName The name of the source that failed validation
     * @param errors List of validation errors
     */
    fun logValidationError(sourceName: String, errors: List<String>) {
        Log.w(TAG, "Source '$sourceName' failed validation:")
        errors.forEach { error ->
            Log.w(TAG, "  - $error")
        }
    }
    
    /**
     * Log source identifier generation.
     * 
     * @param sourceName The original source name
     * @param generatedId The generated identifier
     */
    fun logIdGeneration(sourceName: String, generatedId: String) {
        Log.d(TAG, "Generated ID '$generatedId' for source '$sourceName'")
    }
    
    /**
     * Log a duplicate source identifier conflict.
     * 
     * @param sourceId The conflicting identifier
     * @param attemptedName The name that was attempted
     */
    fun logDuplicateId(sourceId: String, attemptedName: String) {
        Log.w(TAG, "Duplicate source ID '$sourceId' detected for source '$attemptedName'")
    }
    
    /**
     * Log source state change (enable/disable).
     * 
     * @param sourceId The source identifier
     * @param enabled The new enabled state
     */
    fun logStateChange(sourceId: String, enabled: Boolean) {
        val state = if (enabled) "enabled" else "disabled"
        Log.i(TAG, "Source '$sourceId' $state")
    }
    
    /**
     * Log source deletion.
     * 
     * @param sourceId The source identifier
     */
    fun logDeletion(sourceId: String) {
        Log.i(TAG, "Source '$sourceId' deleted")
    }
    
    /**
     * Log a database operation error.
     * 
     * @param operation Description of the database operation
     * @param error The error that occurred
     */
    fun logDatabaseError(operation: String, error: Throwable) {
        Log.e(TAG, "Database error during '$operation'", error)
        if (error.cause != null) {
            Log.e(TAG, "  Caused by: ${error.cause?.javaClass?.simpleName}: ${error.cause?.message}")
        }
    }
    
    /**
     * Log a network operation error.
     * 
     * @param url The URL that failed
     * @param error The error that occurred
     */
    fun logNetworkError(url: String, error: Throwable) {
        Log.e(TAG, "Network request failed: ${error.javaClass.simpleName}")
    }
    
    /**
     * Log a rule syntax error.
     * 
     * @param rule The rule that failed
     * @param reason The reason for the failure
     */
    fun logRuleSyntaxError(rule: String, reason: String) {
        Log.e(TAG, "Invalid rule syntax")
    }
    
    /**
     * Log source usage tracking.
     * 
     * @param sourceId The source identifier
     */
    fun logUsageTracking(sourceId: String) {
        Log.d(TAG, "Tracking usage for source '$sourceId'")
    }
    
    /**
     * Log a warning message.
     * 
     * @param message The warning message
     */
    fun logWarning(message: String) {
        Log.w(TAG, message)
    }

    fun logTvBoxImportFailure(category: String, action: String, locator: String, detail: String, error: Throwable? = null) {
        val errorType = error?.javaClass?.simpleName ?: "none"
        Log.w(TAG, "TVBox import failure: category=$category action=$action errorType=$errorType")
    }
    
    /**
     * Log an info message.
     * 
     * @param message The info message
     */
    fun logInfo(message: String) {
        Log.i(TAG, message)
    }
    
    /**
     * Log a debug message.
     * 
     * @param message The debug message
     */
    fun logDebug(message: String) {
        Log.d(TAG, message)
    }
    
    /**
     * Log an error with full stack trace.
     * 
     * @param message The error message
     * @param error The throwable to log
     */
    fun logError(message: String, error: Throwable) {
        Log.e(TAG, message, error)
        
        // Log the full stack trace for debugging
        val stackTrace = error.stackTraceToString()
        Log.e(TAG, "Stack trace:\n$stackTrace")
    }
}
