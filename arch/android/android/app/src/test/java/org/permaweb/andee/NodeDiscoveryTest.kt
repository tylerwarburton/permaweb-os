package org.permaweb.andee

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.*
import org.junit.Test

class NodeDiscoveryTest {
    private val address = "A".repeat(43)
    private val origin = "https://${"a".repeat(52)}.smoke.solutions"

    /** Remote identity comes from the info/address path; the reader answers it with its address. */
    private fun snap(ip: String?, read: (String) -> NodeMetadata?) = NodeDiscovery.snapshot(
        ip,
        { url -> if (url.endsWith(NodeDiscovery.ADDRESS)) read(url)?.address else null },
        { url -> if (url.endsWith(NodeDiscovery.ADDRESS)) null else read(url) },
    )

    @Test fun tunnelCheckReadsOnlyTheRemoteAddress() {
        val calls = mutableListOf<String>()
        val status = NodeDiscovery.snapshot("192.168.1.2", { url -> calls.add("address $url"); address }) { url ->
            calls.add("info $url")
            NodeMetadata(address, origin)
        }
        assertTrue(status.ready)
        assertEquals(listOf("info http://127.0.0.1:8734${NodeDiscovery.INFO}", "address $origin${NodeDiscovery.ADDRESS}"), calls)
    }

    @Test fun providerAnsweringWithItsOwnIdentityIsUnavailable() {
        // An unattached tunnel subdomain returns the provider node's address, not ours.
        val status = NodeDiscovery.snapshot("192.168.1.2", { "B".repeat(43) }) { NodeMetadata(address, origin) }
        assertFalse(status.ready)
        assertEquals(NodeState.TUNNEL_NOT_LINKED, status.state)
    }

    @Test fun startupTunnelUsesReportedCommitterAndVerifiesTheNode() {
        val label = "uri-did-ar-${"a".repeat(52)}"
        val root = "https://$label.smoke.solutions"
        val calls = mutableListOf<String>()
        val status = snap("192.168.1.2") { url ->
            calls.add(url)
            if (url.startsWith("http://127.")) NodeMetadata(address, null, label, "https://smoke.solutions")
            else NodeMetadata(address, null)
        }
        assertEquals(root, status.url)
        assertEquals(NodeState.ONLINE, status.state)
        assertEquals("smoke.solutions", status.via)
        assertEquals(listOf("http://127.0.0.1:8734${NodeDiscovery.INFO}", root + NodeDiscovery.ADDRESS), calls)
    }

    @Test fun invalidOrUnavailableStartupTunnelNeverAdvertisesLan() {
        val label = "uri-did-ar-${"a".repeat(52)}"
        for ((committer, peer, state) in listOf(
            Triple(null, "https://smoke.solutions", NodeState.TUNNEL_INVALID),
            Triple("not-a-committer", "https://smoke.solutions", NodeState.TUNNEL_INVALID),
            Triple(label, "https://user:pass@smoke.solutions", NodeState.TUNNEL_INVALID),
            Triple(label, "https://smoke.solutions/path", NodeState.TUNNEL_INVALID),
            Triple(label, "https://127.0.0.1", NodeState.TUNNEL_INVALID),
            Triple(label, "https://smoke.solutions", NodeState.TUNNEL_NO_ANSWER),
        )) {
            val status = snap("192.168.1.2") { url ->
                if (url.startsWith("http://127.")) NodeMetadata(address, null, committer, peer) else null
            }
            assertEquals(state, status.state)
            assertFalse(status.ready)
            assertEquals("", status.url)
        }
        val wrongNode = snap(null) { url ->
            if (url.startsWith("http://127.")) NodeMetadata(address, null, label, "https://smoke.solutions")
            else NodeMetadata("B".repeat(43), null)
        }
        assertEquals(NodeState.TUNNEL_NOT_LINKED, wrongNode.state)
    }

    @Test fun bareTunnelRootRoundTripsWithoutSubstitution() {
        val url = origin
        val rows = DiscoveryQr.rowsFor(url)
        // Decode the symbol independently of the renderer, excluding its quiet zone.
        val symbol = BitMatrix(rows.size - 8)
        for (y in 4 until rows.size - 4) for (x in 4 until rows.size - 4) {
            if (rows[y][x]) symbol.set(x - 4, y - 4)
        }
        assertEquals(url, Decoder().decode(symbol).text)
        assertTrue(rows.size > 33)
    }

    @Test fun onlyMatchingReachableNodeProducesTunnelQr() {
        val calls = mutableListOf<String>()
        val status = snap("192.168.1.2") { url ->
            calls.add(url)
            NodeMetadata(address, origin)
        }
        assertTrue(status.ready)
        assertEquals(origin, status.url)
        assertEquals(listOf("http://127.0.0.1:8734${NodeDiscovery.INFO}", origin + NodeDiscovery.ADDRESS), calls)
    }

    @Test fun unreachableOrWrongTunnelDoesNotFallBackToLan() {
        for ((remote, state) in listOf(
            null to NodeState.TUNNEL_NO_ANSWER,
            NodeMetadata("B".repeat(43), origin) to NodeState.TUNNEL_NOT_LINKED,
        )) {
            val status = snap("192.168.1.2") { url ->
                if (url.startsWith("http://127.")) NodeMetadata(address, origin) else remote
            }
            assertFalse(status.ready)
            assertEquals("", status.url)
            assertEquals(state, status.state)
        }
    }

    @Test fun changedConfigurationIsReadOnEverySnapshot() {
        for (host in listOf(origin, "https://next.smoke.solutions")) {
            val status = snap(null) { NodeMetadata(address, host) }
            assertEquals(host, status.url)
        }
    }

    @Test fun startingAndLocalOnlyStatesStayDistinct() {
        assertEquals(NodeStatus.booting(), snap(null) { null })
        assertEquals(NodeState.NO_NETWORK, snap(null) { NodeMetadata(address, null) }.state)
        val local = snap("192.168.1.2") { NodeMetadata(address, null) }
        assertTrue(local.ready)
        assertEquals(NodeState.LOCAL, local.state)
        assertEquals("http://192.168.1.2:8734", local.url)
        assertFalse(DiscoveryQr.rowsFor("").any { it.any { cell -> cell } })
    }

    @Test fun credentialsQueriesPathsAndPlaceholdersAreNotQrPayloads() {
        for (value in listOf("https://owner:secret@example.com", "https://example.com/?key=secret",
            "https://example.com/#secret", "https://example.com/path", "file:///etc/passwd",
            "https://**[base32 encoded node address]**.smoke.solutions")) {
            assertNull(NodeDiscovery.origin(value))
            val status = snap("192.168.1.2") { NodeMetadata(address, value) }
            assertFalse(status.ready)
            assertEquals("", status.url)
        }
    }

    @Test fun everyStateHasPlainWordingAndAShortNodeId() {
        val id = "haqi5CyIG7-C8_0LSnXfPcmfuIcya5WuOGz8H4FrIis"
        for (state in NodeState.values()) {
            val status = NodeStatus(state == NodeState.ONLINE, null, "", state, id, "iloveao.online")
            val title = NodeStatusText.title(status, false)
            val detail = NodeStatusText.detail(status, false)
            assertTrue(title.isNotBlank() && title != title.uppercase())
            assertTrue(detail.endsWith("Node haqi5C…4FrIis"))
        }
        val waiting = NodeStatus(false, null, "", NodeState.TUNNEL_NOT_LINKED, id, "iloveao.online")
        assertEquals("Waiting for iloveao.online", NodeStatusText.title(waiting, false))
        assertEquals("the tunnel service", NodeStatusText.title(waiting.copy(via = ""), false).removePrefix("Waiting for "))
        assertEquals("New setup ready", NodeStatusText.title(waiting, true))
        assertFalse(NodeStatusText.detail(NodeStatus.booting(), false).contains("Node "))
    }
}
