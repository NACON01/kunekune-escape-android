package com.nacon01.kunekune

/** Keeps the last terminal failure visible after owner resources are released. */
class TerminalFailureStatus {
    @Volatile
    private var message: String? = null

    fun record(failureMessage: String) {
        message = failureMessage
    }

    fun current(): String? = message

    fun acknowledge() {
        message = null
    }
}
