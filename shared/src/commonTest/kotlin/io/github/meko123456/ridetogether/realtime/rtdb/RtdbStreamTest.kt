package io.github.meko123456.ridetogether.realtime.rtdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

class RtdbStreamTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun parse(stream: String): List<ServerSentEvent> {
        val parser = ServerSentEventParser()
        return stream.lines().mapNotNull(parser::feed)
    }

    private fun tree(vararg events: String): RtdbTree {
        val tree = RtdbTree()
        parse(events.joinToString("")).mapNotNull(RtdbStreamEvent::from).forEach(tree::apply)
        return tree
    }

    @Test
    fun `a stream is cut into events at blank lines and comments are ignored`() {
        val events = parse(
            ": a comment the server may send\n" +
                "event: put\n" +
                "data: {\"path\":\"/\",\"data\":{\"a\":1}}\n" +
                "\n" +
                "event: keep-alive\n" +
                "data: null\n" +
                "\n",
        )
        assertEquals(
            listOf(
                ServerSentEvent("put", "{\"path\":\"/\",\"data\":{\"a\":1}}"),
                ServerSentEvent("keep-alive", "null"),
            ),
            events,
        )
    }

    @Test
    fun `data split over several lines is joined back together`() {
        assertEquals(listOf(ServerSentEvent("put", "{\n\"path\":\"/\"}")), parse("event: put\ndata: {\ndata: \"path\":\"/\"}\n\n"))
    }

    @Test
    fun `every message the database sends is understood and anything else is not guessed at`() {
        assertEquals(RtdbStreamEvent.Put("/a", json("1")), RtdbStreamEvent.from(ServerSentEvent("put", """{"path":"/a","data":1}""")))
        assertEquals(
            RtdbStreamEvent.Patch("/", json("""{"b":2}""") as JsonObject),
            RtdbStreamEvent.from(ServerSentEvent("patch", """{"path":"/","data":{"b":2}}""")),
        )
        assertEquals(RtdbStreamEvent.KeepAlive, RtdbStreamEvent.from(ServerSentEvent("keep-alive", "null")))
        assertEquals(RtdbStreamEvent.Cancel, RtdbStreamEvent.from(ServerSentEvent("cancel", "null")))
        assertEquals(RtdbStreamEvent.AuthRevoked, RtdbStreamEvent.from(ServerSentEvent("auth_revoked", "credential is no longer valid")))
        assertNull(RtdbStreamEvent.from(ServerSentEvent("put", "not json")))
        assertNull(RtdbStreamEvent.from(ServerSentEvent("teleport", "null")))
    }

    @Test
    fun `the first put is the whole location and later ones replace a part of it`() {
        val t = tree(
            "event: put\ndata: {\"path\":\"/\",\"data\":{\"meta\":{\"state\":\"LOBBY\"},\"members\":{\"a\":{\"role\":\"LEADER\"}}}}\n\n",
            "event: put\ndata: {\"path\":\"/meta/state\",\"data\":\"RIDING\"}\n\n",
        )
        assertEquals(json("""{"meta":{"state":"RIDING"},"members":{"a":{"role":"LEADER"}}}"""), t.root)
    }

    @Test
    fun `a patch replaces only the children it names`() {
        val t = tree(
            "event: put\ndata: {\"path\":\"/\",\"data\":{\"a\":{\"lat\":1},\"b\":{\"lat\":2}}}\n\n",
            "event: patch\ndata: {\"path\":\"/\",\"data\":{\"b\":{\"lat\":3},\"c\":{\"lat\":4}}}\n\n",
        )
        assertEquals(json("""{"a":{"lat":1},"b":{"lat":3},"c":{"lat":4}}"""), t.root)
    }

    @Test
    fun `deleting the last child deletes its parent the way the database does`() {
        val t = tree(
            "event: put\ndata: {\"path\":\"/\",\"data\":{\"members\":{\"a\":{\"role\":\"RIDER\"}},\"meta\":{\"name\":\"x\"}}}\n\n",
            "event: put\ndata: {\"path\":\"/members/a\",\"data\":null}\n\n",
        )
        assertEquals(json("""{"meta":{"name":"x"}}"""), t.root)
        t.apply(RtdbStreamEvent.Put("/meta", JsonNull))
        assertNull(t.root, "nothing left is absence, not an empty object")
    }

    @Test
    fun `nulls and empty objects inside a put are absence too`() {
        val t = tree("event: put\ndata: {\"path\":\"/\",\"data\":{\"a\":null,\"b\":{},\"c\":{\"d\":null},\"e\":1}}\n\n")
        assertEquals(json("""{"e":1}"""), t.root)
    }
}
