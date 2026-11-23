package org.permaweb.andee

import java.net.URI

/** Where the node is in becoming reachable; each state maps to one plain-language message. */
internal enum class NodeState {
    STARTING,
    NO_NETWORK,
    LOCAL,
    TUNNEL_INVALID,
    TUNNEL_NO_ANSWER,
    TUNNEL_NOT_LINKED,
    ONLINE,
}

internal data class NodeStatus(
    val ready: Boolean,
    val ip: String?,
    val url: String,
    val state: NodeState,
    val address: String = "",
    // Host of the tunnel service or configured public origin, for messages only.
    val via: String = "",
) {
    companion object {
        fun booting() = NodeStatus(false, null, "", NodeState.STARTING)
    }
}

internal data class NodeMetadata(
    val address: String,
    val locationUrl: String?,
    val committer: String? = null,
    val tunnelPeer: String? = null,
)

/** Presentation of existing meta@1.0 data; no tunnel or identity is created here. */
internal object NodeDiscovery {
    const val INFO = "/~meta@1.0/info"
    // The tunnel check needs only identity; the full info map is too large to cross a tunnel in time.
    const val ADDRESS = "$INFO/address"
    private val addressPattern = Regex("[A-Za-z0-9_-]{43}")

    fun origin(value: String): String? = try {
        val uri = URI(value)
        if (value.length > 512 || uri.scheme !in listOf("http", "https") ||
            uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null ||
            uri.rawFragment != null || uri.rawPath !in listOf("", "/") ||
            uri.port !in -1..65535 || uri.port == 0
        ) null else "${uri.scheme}://${uri.rawAuthority}"
    } catch (_: Exception) { null }

    private fun tunnelOrigin(committer: String?, peer: String): String? {
        // meta already reports HyperBEAM's DNS-safe committer. Do not re-encode identity.
        if (committer == null || !Regex("uri-did-ar-[a-z0-9-]{52}").matches(committer)) return null
        val provider = origin(peer)?.let(::URI) ?: return null
        if (!provider.host.contains('.') || provider.host.contains(':') ||
            provider.host.split('.').any { !Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?").matches(it) } ||
            provider.host.all { it.isDigit() || it == '.' }
        ) return null
        return origin("${provider.scheme}://$committer.${provider.rawAuthority}")
    }

    fun snapshot(ip: String?, readAddress: (String) -> String?, read: (String) -> NodeMetadata?): NodeStatus {
        val local = read("http://127.0.0.1:8734$INFO") ?: return NodeStatus.booting()
        if (!addressPattern.matches(local.address)) return NodeStatus.booting()
        val configured = local.locationUrl ?: local.tunnelPeer?.let {
            tunnelOrigin(local.committer, it) ?: ""
        }
        if (configured != null) {
            val via = host(local.tunnelPeer ?: configured)
            fun unavailable(state: NodeState) = NodeStatus(false, ip, "", state, local.address, via)
            val publicOrigin = origin(configured) ?: return unavailable(NodeState.TUNNEL_INVALID)
            val remote = readAddress(publicOrigin + ADDRESS) ?: return unavailable(NodeState.TUNNEL_NO_ANSWER)
            // A reachable gateway alone is insufficient: it must reach this node.
            // (Providers answer unattached subdomains with their own identity.)
            if (remote != local.address) return unavailable(NodeState.TUNNEL_NOT_LINKED)
            return NodeStatus(true, ip, publicOrigin, NodeState.ONLINE, local.address, via)
        }
        return if (ip == null) NodeStatus(false, null, "", NodeState.NO_NETWORK, local.address)
        else NodeStatus(true, ip, "http://$ip:8734", NodeState.LOCAL, local.address)
    }

    private fun host(value: String): String = try {
        URI(value).host?.takeIf { it.isNotEmpty() && it.length <= 253 } ?: ""
    } catch (_: Exception) { "" }
}

/** User-facing wording for a [NodeStatus]: a short title and what to do next. */
internal object NodeStatusText {
    fun title(status: NodeStatus, configPending: Boolean): String = when {
        configPending -> "New setup ready"
        else -> when (status.state) {
            NodeState.STARTING -> "Starting your node"
            NodeState.NO_NETWORK -> "No network connection"
            NodeState.LOCAL -> "Online on this Wi-Fi"
            NodeState.TUNNEL_INVALID -> "Tunnel setting can't be used"
            NodeState.TUNNEL_NO_ANSWER -> "Connecting to ${service(status)}"
            NodeState.TUNNEL_NOT_LINKED -> "Waiting for ${service(status)}"
            NodeState.ONLINE -> "Online"
        }
    }

    fun detail(status: NodeStatus, configPending: Boolean): String {
        val next = when {
            configPending -> "Tap Apply & close, then open the app again to start with it."
            else -> when (status.state) {
                NodeState.STARTING -> "This can take a minute after the app opens."
                NodeState.NO_NETWORK -> "Your node is running. Connect this phone to Wi-Fi to reach it."
                NodeState.LOCAL ->
                    "Scan the code with the PermawebOS extension on a computer on this Wi-Fi. " +
                        "To reach it from anywhere, tap Configure and scan a setup code with a tunnel."
                NodeState.TUNNEL_INVALID ->
                    "Your node is running, but its public address is invalid. Tap Configure and scan a new setup code."
                NodeState.TUNNEL_NO_ANSWER ->
                    "Your node is running. ${service(status)} isn't answering yet. Keep this app open on Wi-Fi; " +
                        "if this lasts more than a few minutes, the service may be down."
                NodeState.TUNNEL_NOT_LINKED ->
                    "Your node is running, but ${service(status)} hasn't linked to it yet. " +
                        "Keep this app open on Wi-Fi; it retries on its own."
                NodeState.ONLINE -> "Scan the code with the PermawebOS extension, or tap the address to open your node."
            }
        }
        return if (status.address.isEmpty()) next else "$next\nNode ${shortAddress(status.address)}"
    }

    /** First and last six characters: enough to match the node shown in the extension. */
    fun shortAddress(address: String): String =
        if (address.length <= 14) address else "${address.take(6)}…${address.takeLast(6)}"

    private fun service(status: NodeStatus) = status.via.ifEmpty { "the tunnel service" }
}
