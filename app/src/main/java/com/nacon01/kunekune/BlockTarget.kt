package com.nacon01.kunekune

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** A configured app or web destination that can be blocked. */
sealed class BlockTarget private constructor() {
    abstract val id: String

    data class App(
        val packageName: String,
        val label: String
    ) : BlockTarget() {
        init {
            require(packageName == normalizePackageName(packageName)) {
                "Package name must be a normalized, nonblank value"
            }
            require(label.trim().isNotEmpty()) { "App label must not be blank" }
        }

        override val id: String = appId(packageName)

        override fun toString(): String = "App($packageName)"
    }

    data class Domain(
        val host: String,
        val includeSubdomains: Boolean,
        val launchUrl: String
    ) : BlockTarget() {
        init {
            require(host == normalizeHost(host)) { "Domain host must be canonical ASCII" }
            require(launchUrl == normalizeLaunchUrl(launchUrl)) {
                "Launch URL must be a normalized HTTP(S) URL"
            }
            require(normalizeHostFromUrl(launchUrl) == host) {
                "Launch URL host must match the domain target"
            }
        }

        override val id: String = domainId(host, includeSubdomains, launchUrl)

        fun matches(hostOrUrl: String): Boolean = matchesDomain(host, includeSubdomains, hostOrUrl)

        override fun toString(): String = "Domain($host)"
    }

    companion object {
        fun app(packageName: String, label: String): App = App(
            packageName = normalizePackageName(packageName),
            label = label.trim()
        )

        fun domain(
            hostOrUrl: String,
            includeSubdomains: Boolean,
            launchUrl: String = if (looksLikeUrl(hostOrUrl)) hostOrUrl.trim() else "https://${hostOrUrl.trim()}"
        ): Domain {
            val host = normalizeHostInput(hostOrUrl)
            val normalizedUrl = normalizeLaunchUrl(launchUrl)
            require(normalizeHostFromUrl(normalizedUrl) == host) {
                "Launch URL host must match the domain target"
            }
            return Domain(host, includeSubdomains, normalizedUrl)
        }

        internal fun appId(packageName: String): String = "app:$packageName"

        internal fun domainId(host: String, includeSubdomains: Boolean, launchUrl: String): String =
            "domain:$host|subdomains=$includeSubdomains|url=$launchUrl"

        internal fun normalizePackageName(value: String): String = value.trim().also {
            require(it.isNotEmpty()) { "Package name must not be blank" }
        }

        internal fun normalizeHostInput(value: String): String {
            val input = value.trim()
            require(input.isNotEmpty()) { "Host must not be blank" }
            return if (looksLikeUrl(input)) {
                normalizeHostFromUrl(input)
            } else {
                require(!input.contains('/') && !input.contains('?') && !input.contains('#')) {
                    "Bare host contains URL syntax"
                }
                normalizeHost(parseAuthorityHost(input))
            }
        }

        internal fun normalizeLaunchUrl(value: String): String {
            val input = value.trim()
            val uri = parseHttpUri(input)
            val host = normalizeHostFromUrl(input)
            require(uri.userInfo == null) { "Credentials are not allowed" }
            require(uri.port == -1 || uri.port in 0..65535) { "Invalid URL port" }
            val scheme = uri.scheme.lowercase(Locale.ROOT)
            val authority = buildString {
                append(host)
                if (uri.port != -1) append(':').append(uri.port)
            }
            return URI(
                scheme,
                authority,
                uri.path ?: "",
                uri.query,
                uri.fragment
            ).toASCIIString()
        }

        internal fun normalizeHostFromUrl(value: String): String {
            val uri = parseHttpUri(value)
            require(uri.userInfo == null) { "Credentials are not allowed" }
            require(uri.port == -1 || uri.port in 0..65535) { "Invalid URL port" }
            return normalizeHost(parseAuthorityHost(uri.rawAuthority ?: ""))
        }

        internal fun normalizeHost(value: String): String {
            var host = value.trim()
            while (host.endsWith('.')) host = host.dropLast(1)
            require(host.isNotEmpty()) { "Host must not be blank" }
            require(!host.contains(':')) { "IP addresses are not supported as domain targets" }
            require(!looksLikeIpAddress(host)) { "IP addresses are not supported as domain targets" }
            val ascii = try {
                IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid host", exception)
            }
            require(ascii.contains('.')) { "A domain target must contain a dot" }
            require(ascii.length <= 253) { "Host is too long" }
            val labels = ascii.split('.')
            require(labels.all { it.isNotEmpty() && it.length <= 63 }) { "Invalid host label" }
            require(labels.all { it.first() != '-' && it.last() != '-' }) { "Invalid host label" }
            require(labels.all { label -> label.all { it.isLetterOrDigit() || it == '-' } }) {
                "Invalid host label"
            }
            return ascii
        }

        internal fun matchesDomain(targetHost: String, includeSubdomains: Boolean, value: String): Boolean {
            val candidate = try {
                if (looksLikeUrl(value.trim())) normalizeHostFromUrl(value) else normalizeHostInput(value)
            } catch (_: IllegalArgumentException) {
                return false
            }
            return candidate == targetHost ||
                (includeSubdomains && candidate.endsWith(".$targetHost"))
        }

        private fun looksLikeUrl(value: String): Boolean =
            value.substringBefore(':', missingDelimiterValue = "").equals("http", true) ||
                value.substringBefore(':', missingDelimiterValue = "").equals("https", true)

        private fun parseHttpUri(value: String): URI {
            val uri = try {
                URI(value)
            } catch (exception: URISyntaxException) {
                throw IllegalArgumentException("Invalid URL", exception)
            }
            require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
                "Only HTTP(S) URLs are supported"
            }
            require(uri.rawAuthority != null && uri.rawAuthority.isNotEmpty()) {
                "URL must have a host"
            }
            return uri
        }

        private fun parseAuthorityHost(authority: String): String {
            require(authority.isNotEmpty() && !authority.contains('@')) { "Invalid host authority" }
            val withoutPort = if (authority.count { it == ':' } == 1) {
                val separator = authority.lastIndexOf(':')
                val port = authority.substring(separator + 1)
                require(port.isEmpty() || port.all(Char::isDigit)) { "Invalid port" }
                if (port.isNotEmpty()) require(port.toLongOrNull() in 0L..65535L) { "Invalid port" }
                authority.substring(0, separator)
            } else authority
            require(withoutPort.isNotEmpty()) { "Host must not be blank" }
            return withoutPort
        }

        private fun looksLikeIpAddress(value: String): Boolean =
            value.all { it.isDigit() || it == '.' } && value.any { it == '.' } ||
                value.contains(':')
    }
}

typealias AppBlockTarget = BlockTarget.App
typealias DomainBlockTarget = BlockTarget.Domain
