package com.example.data.util

import java.net.SocketTimeoutException
import java.net.UnknownHostException

object ErrorMessages {

    /** Turns exceptions (including yt-dlp's long error output) into one readable line. */
    fun friendly(e: Throwable): String {
        val message = e.message.orEmpty()
        val ytDlpError = message.lines()
            .lastOrNull { it.contains("ERROR:") }
            ?.substringAfter("ERROR:")
            ?.trim()
        val text = when {
            e is UnknownHostException -> "No internet connection, or the server could not be found"
            e is SocketTimeoutException -> "The connection timed out"
            ytDlpError != null -> {
                val needsLogin = listOf("login", "log in", "sign in", "private", "cookies", "rate-limit")
                    .any { ytDlpError.contains(it, ignoreCase = true) }
                if (needsLogin) "$ytDlpError — log in to the site in the Browser tab, then retry" else ytDlpError
            }
            message.isNotBlank() -> message.lines().first()
            else -> e.javaClass.simpleName
        }
        return text.take(300)
    }
}
