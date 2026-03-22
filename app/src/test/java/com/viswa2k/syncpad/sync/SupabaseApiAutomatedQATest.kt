package com.viswa2k.syncpad.sync

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SupabaseApiAutomatedQATest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun debugMode_usesBlogsTestTable() {
        assertEquals("blogs_test", SupabaseApi.resolveBlogsTable(useTestTable = true))
        assertEquals("blogs", SupabaseApi.resolveBlogsTable(useTestTable = false))
    }

    @Test
    fun bulkInsert_chunksUploadAndTargetsBlogsTest() = runTest {
        // 250 rows => 3 requests (100 + 100 + 50)
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(201).setBody("[]"))
        }

        val api = SupabaseApi(
            configuredBaseUrl = server.url("/").toString().trimEnd('/'),
            configuredApiKey = "test-key",
            useTestTable = true
        )

        val rows = (1..250).map { id ->
            BlogDto(
                id = id.toLong(),
                title = "Title $id",
                content = "Content $id",
                createdAt = 1700000000000L + id,
                updatedAt = 1700000000000L + id,
                deviceId = "qa-device"
            )
        }

        val result = api.upsertBlogs(rows)
        assertTrue(result.isSuccess)
        assertEquals(250, result.getOrNull())

        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        val req3 = server.takeRequest()

        assertTrue(req1.path?.startsWith("/rest/v1/blogs_test") == true)
        assertTrue(req2.path?.startsWith("/rest/v1/blogs_test") == true)
        assertTrue(req3.path?.startsWith("/rest/v1/blogs_test") == true)
    }

    @Test
    fun partialFetch_streamAcrossPages_targetsBlogsTest() = runTest {
        // First page full (500) => should request second page with id=gt.500
        server.enqueue(MockResponse().setResponseCode(200).setBody(createBlogsJson(startId = 1, count = 500)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(createBlogsJson(startId = 501, count = 5)))

        val api = SupabaseApi(
            configuredBaseUrl = server.url("/").toString().trimEnd('/'),
            configuredApiKey = "test-key",
            useTestTable = true
        )

        var received = 0
        val result = api.streamBlogsAfter(afterTimestamp = 0L) { received++ }

        assertTrue(result.isSuccess)
        assertEquals(505, result.getOrNull())
        assertEquals(505, received)

        val req1 = server.takeRequest()
        val req2 = server.takeRequest()

        assertTrue(req1.path?.contains("/rest/v1/blogs_test") == true)
        assertTrue(req1.path?.contains("id=gt.0") == true)

        assertTrue(req2.path?.contains("/rest/v1/blogs_test") == true)
        assertTrue(req2.path?.contains("id=gt.500") == true)
    }

    @Test
    fun continueFromCursor_resumeTargetsBlogsTestAndUsesCursor() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(createBlogsJson(startId = 1201, count = 2)))

        val api = SupabaseApi(
            configuredBaseUrl = server.url("/").toString().trimEnd('/'),
            configuredApiKey = "test-key",
            useTestTable = true
        )

        var received = 0
        val result = api.streamBlogsAfterId(afterTimestamp = 0L, afterId = 1199L) { received++ }

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull())
        assertEquals(2, received)

        val request = server.takeRequest()
        assertTrue(request.path?.contains("/rest/v1/blogs_test") == true)
        assertTrue(request.path?.contains("id=gt.1199") == true)
    }

    @Test
    fun missingDebugTable_fallsBackToBlogsAndRetries() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"code":"PGRST205","message":"Could not find the table 'public.blogs_test' in the schema cache"}""")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Range", "0-0/12")
                .setBody("[]")
        )

        val api = SupabaseApi(
            configuredBaseUrl = server.url("/").toString().trimEnd('/'),
            configuredApiKey = "test-key",
            useTestTable = true
        )

        val result = api.getServerCount(afterTimestamp = 0L)

        assertTrue(result.isSuccess)
        assertEquals(12, result.getOrNull())
        assertEquals("blogs", api.getActiveBlogsTableName())

        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        assertTrue(req1.path?.contains("/rest/v1/blogs_test") == true)
        assertTrue(req2.path?.contains("/rest/v1/blogs") == true)
    }

    private fun createBlogsJson(startId: Int, count: Int): String {
        val rows = (0 until count).joinToString(",") { offset ->
            val id = startId + offset
            """
            {
              "id": $id,
              "title": "Title $id",
              "content": "Content $id",
              "title_prefix": "T",
              "created_at": 1700000000000,
              "updated_at": 1700000000000,
              "device_id": "qa-device",
              "is_deleted": false,
              "deleted_at": null
            }
            """.trimIndent()
        }
        return "[$rows]"
    }
}
