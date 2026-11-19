package app.transmit.access

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** What OpenRouter's `GET /api/v1/key` says about the managed key. */
class OpenRouterAllowanceTest {
    private lateinit var server: MockWebServer
    private val now = 1_800_000_000_000L

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun read(status: Int, body: String): AllowanceCheck {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
        return runBlocking { OpenRouterAllowance(OkHttpClient(), server.url("/api/v1").toString()) { now }.read("sk-or-v1-managed") }
    }

    @Test
    fun aReadingCoversSpendingUpToOpenRoutersLag() {
        val r = read(200, """{"data":{"label":"transmit-x","limit":4.5,"limit_remaining":1.25,"usage":3.25}}""")
        assertEquals(AllowanceCheck.Reading(1.25, now - OpenRouterAllowance.LAG_MS), r)
        val req = server.takeRequest()
        assertEquals("/api/v1/key", req.path)
        assertEquals("Bearer sk-or-v1-managed", req.getHeader("Authorization"))
        // Overshoot past the limit reads as nothing left, not a negative allowance.
        assertEquals(AllowanceCheck.Reading(0.0, now - OpenRouterAllowance.LAG_MS), read(200, """{"data":{"limit_remaining":-0.2}}"""))
        assertEquals(AllowanceCheck.Unavailable, read(200, """{"data":{"limit_remaining":null}}"""))
    }

    @Test
    fun only403KeyLimitIsUsedUp() {
        assertEquals(AllowanceCheck.UsedUp, read(403, """{"error":{"code":403,"message":"Key limit exceeded (total limit)"}}"""))
        assertEquals(AllowanceCheck.Unavailable, read(403, """{"error":{"code":403,"message":"Workspace budget exceeded"}}"""))
    }

    @Test
    fun every401IsTheKeyBeingRefusedWhateverTheWording() {
        assertEquals(AllowanceCheck.KeyRejected, read(401, """{"error":{"code":401,"message":"API key expired."}}"""))
        assertEquals(AllowanceCheck.KeyRejected, read(401, """{"error":{"code":401,"message":"User not found."}}"""))
        assertEquals(AllowanceCheck.KeyRejected, read(401, "unauthorized"))
    }

    @Test
    fun anythingElseIsAServiceProblemNeverAPaywall() {
        assertEquals(AllowanceCheck.Unavailable, read(429, """{"error":{"message":"Rate limited"}}"""))
        assertEquals(AllowanceCheck.Unavailable, read(500, "oops"))
        assertEquals(AllowanceCheck.Unavailable, read(404, """{"error":{"message":"Not found"}}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(
            AllowanceCheck.Unavailable,
            runBlocking { OpenRouterAllowance(OkHttpClient(), server.url("/api/v1").toString()) { now }.read("k") },
        )
    }
}
