package rtok.proxy

import rtok.config.LanePolicy
import rtok.config.Lanes
import rtok.config.RouteRule
import rtok.config.RoutingPolicy
import rtok.lane.LaneKinds
import kotlin.test.*

/** Port of src/proxy/lane.rs tests (rstest cases as loops). */
class LaneTest {
    private fun laneOf(
        path: String,
        header: String? = null,
    ) = Lane.classify(path, header).let { it.lane to it.path }

    @Test fun pathsNameTheirLane() {
        val cases =
            listOf(
                "/v1/messages" to Lane.Agent,
                "/v1/chat/completions" to Lane.Agent,
                "/v1beta/models/gemini-2.0-flash:generateContent" to Lane.Agent,
                "/v1/something/new" to Lane.Agent,
                "/v1/messages/batches" to Lane.Batch,
                "/v1/messages/batches/msgbatch_1/results" to Lane.Batch,
                "/v1/batches/batch_1/cancel" to Lane.Batch,
                "/v1beta/models/gemini-2.0-flash:batchGenerateContent" to Lane.Batch,
                "/v1/files" to Lane.Files,
                "/v1/files/file_1/content" to Lane.Files,
                "/upload/v1beta/files" to Lane.Files,
                "/v1/embeddings" to Lane.Embeddings,
                "/v1beta/models/text-embedding-004:embedContent" to Lane.Embeddings,
                "/v1/models" to Lane.Meta,
                "/v1/models/gpt-5" to Lane.Meta,
                "/v1/messages/count_tokens" to Lane.Meta,
                "/v1beta/models/gemini-2.0-flash:countTokens" to Lane.Meta,
            )
        for ((path, lane) in cases) assertEquals(lane to path, laneOf(path), path)
    }

    @Test fun markerPicksAmongWhatAPathCannotSay() {
        assertEquals(Lane.Bulk, laneOf("/v1/messages", "Bulk").first)
        assertEquals(Lane.Internal, laneOf("/v1/responses", "internal").first)
        assertEquals(Lane.Batch, laneOf("/v1/messages/batches", "bulk").first)
        assertEquals(Lane.Agent, laneOf("/v1/messages", "turbo").first)
    }

    @Test fun prefixIsStrippedAndBeatsTheHeader() {
        assertEquals(Lane.Bulk to "/v1/messages", laneOf("/lane/bulk/v1/messages", "internal"))
        assertEquals(Lane.Files to "/v1/files/f1", laneOf("/lane/bulk/v1/files/f1"))
    }

    @Test fun foreignPrefixesAreForwardedUntouched() {
        for (p in listOf("/lane/turbo/v1/messages", "/lane/bulk", "/lane/", "/lanes/bulk/v1/messages")) {
            assertEquals(Lane.Agent to p, laneOf(p), p)
        }
    }

    @Test fun onlyTheAgentLaneRewritesByDefault() {
        for (lane in Lane.entries) {
            val p = lane.policy(Lanes())
            val allOn = p.compress && p.toon && p.toolsRewrite && p.contextManagement && p.semanticCache
            assertEquals(lane == Lane.Agent, allOn, lane.laneName)
            assertEquals(0uL, p.timeoutS, lane.laneName)
            if (lane != Lane.Agent) assertEquals(LanePolicy(), p, lane.laneName)
        }
    }

    @Test fun batchAndFilesIgnoreTheConfig() {
        val on = LanePolicy(compress = true, toolsRewrite = true)
        val lanes = Lanes(bulk = on, embeddings = on, meta = on, internal = on)
        assertEquals(on, Lane.Bulk.policy(lanes))
        assertEquals(on, Lane.Internal.policy(lanes))
        for (lane in listOf(Lane.Batch, Lane.Files)) {
            assertTrue(lane.passesThrough)
            assertEquals(LanePolicy(), lane.policy(lanes))
        }
        assertFalse(Lane.Bulk.passesThrough)
    }

    @Test fun agentBatchAndFilesKeepTheirUpstreamAndNoCap() {
        val moved = LanePolicy(upstream = "http://elsewhere", maxInFlight = 1u)
        val lanes = Lanes(bulk = moved, embeddings = moved, meta = moved, internal = moved)
        for (lane in listOf(Lane.Agent, Lane.Batch, Lane.Files)) {
            val p = lane.policy(lanes)
            assertEquals("" to 0u, p.upstream to p.maxInFlight, lane.laneName)
        }
        assertEquals(moved, Lane.Bulk.policy(lanes))
    }

    @Test fun onlyInternalAndBulkCanBeRoutedAndOnlyWhenEnabled() {
        val rule = RouteRule(from = listOf("big"), to = "small", gate = "bench")
        val routing = RoutingPolicy(bulk = rule, internal = rule)
        for (lane in Lane.entries) assertNull(lane.route(routing), "${lane.laneName} while off")
        val on = routing.copy(enabled = true)
        for (lane in Lane.entries) {
            assertEquals(lane == Lane.Bulk || lane == Lane.Internal, lane.route(on) == rule, lane.laneName)
        }
    }

    @Test fun everyKindReadsBackAsItsLane() {
        for (lane in Lane.entries) assertEquals(lane.laneName, LaneKinds.laneOfKind(lane.kind))
    }

    @Test fun kindsStayApiRequests() {
        for (lane in Lane.entries) assertTrue(LaneKinds.isApiRequest(lane.kind), lane.laneName)
        assertFalse(LaneKinds.isApiRequest("hook"))
        assertFalse(LaneKinds.isApiRequest("api_requests"))
    }
}
