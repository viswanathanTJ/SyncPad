package com.viswa2k.syncpad.sync

import com.viswa2k.syncpad.BuildConfig
import com.viswa2k.syncpad.data.entity.BlogEntity
import com.viswa2k.syncpad.util.AppLogger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import java.net.SocketException
import java.net.UnknownHostException
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Blog data transfer object for Supabase API.
 * Maps to Supabase blogs table columns.
 */
data class BlogDto(
    val id: Long? = null,
    val title: String,
    val content: String?,
    @SerializedName("title_prefix") val titlePrefix: String? = null, // May be null or incorrect from server
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("updated_at") val updatedAt: Long,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("is_deleted") val isDeleted: Boolean = false,
    @SerializedName("deleted_at") val deletedAt: Long? = null
) {
    companion object {
        /**
         * Default max depth for prefix generation.
         * Should match settings but use 5 as fallback.
         */
        const val DEFAULT_MAX_DEPTH = 5
        
        fun fromBlogEntity(entity: BlogEntity): BlogDto {
            return BlogDto(
                id = entity.id,
                title = entity.title,
                content = entity.content,
                titlePrefix = entity.titlePrefix,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                deviceId = entity.deviceId,
                isDeleted = entity.isDeleted,
                deletedAt = entity.deletedAt
            )
        }
    }
    
    /**
     * Convert to BlogEntity with LOCALLY calculated title_prefix.
     * This ensures title_prefix = UPPER(SUBSTR(title, 1, maxDepth))
     * regardless of what the server sends.
     */
    fun toBlogEntity(maxDepth: Int = DEFAULT_MAX_DEPTH): BlogEntity {
        // ALWAYS recalculate title_prefix locally from title
        val calculatedPrefix = BlogEntity.generateTitlePrefix(title, maxDepth)
        
        return BlogEntity(
            id = id ?: 0,
            title = title,
            content = content,
            titlePrefix = calculatedPrefix,
            createdAt = createdAt,
            updatedAt = updatedAt,
            deviceId = deviceId,
            isDeleted = isDeleted,
            deletedAt = deletedAt
        )
    }
}

/**
 * Simple DTO for parsing deleted blog IDs from recycle_bin table.
 */
private data class DeletedIdDto(val id: Long? = null)

/**
 * Exception thrown when network connectivity is lost during sync.
 * This is a recoverable error - sync can resume when connection is restored.
 */
class NetworkInterruptedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Supabase REST API client for blog sync.
 * Uses streaming JSON parsing to avoid OOM with large datasets.
 */
@Singleton
class SupabaseApi @Inject constructor() {

    companion object {
        private const val TAG = "SupabaseApi"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val PAGE_SIZE = 500 // Blogs per page (increased from 50 for faster sync)
    }

    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create()

    private val client: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            AppLogger.d(TAG, message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    /**
     * Stream blogs from Supabase that have been updated after the given timestamp.
     * Uses streaming JSON parsing to avoid loading entire response into memory.
     * Calls onBlog callback for each blog as it's parsed.
     * 
     * @param afterTimestamp Only fetch blogs created/updated after this timestamp
     * @param onBlog Callback invoked for each blog - should insert to database
     * @return Result with total count of blogs processed
     */
    suspend fun streamBlogsAfter(
        afterTimestamp: Long,
        onBlog: suspend (BlogDto) -> Unit
    ): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = BuildConfig.SYNC_BASE_URL
                val apiKey = BuildConfig.SYNC_API_KEY

                if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                    return@withContext Result.failure(Exception("Sync not configured"))
                }

                var totalCount = 0
                var offset = 0
                var hasMore = true

                while (hasMore) {
                    val url = "$baseUrl/rest/v1/blogs?select=*" +
                            "&or=(created_at.gte.$afterTimestamp,updated_at.gte.$afterTimestamp)" +
                            "&order=id.asc" +
                            "&limit=$PAGE_SIZE" +
                            "&offset=$offset"

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Prefer", "return=representation")
                        .get()
                        .build()

                    AppLogger.d(TAG, "Streaming blogs page at offset $offset")

                    val response = client.newCall(request).execute()

                    // Track page count outside response block for hasMore check
                    var pageCount = 0
                    
                    // Use response.use{} to ensure connection is closed even on cancellation
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val errorBody = resp.body?.string() ?: "Unknown error"
                            AppLogger.e(TAG, "API error: ${resp.code} - $errorBody")
                            return@withContext Result.failure(Exception("Failed to fetch blogs: ${resp.code}"))
                        }

                        val body = resp.body
                        if (body == null) {
                            hasMore = false
                            return@use
                        }

                        // Stream parse the JSON array
                        body.byteStream().use { inputStream ->
                            InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
                                JsonReader(reader).use { jsonReader ->
                                    jsonReader.beginArray()
                                    while (jsonReader.hasNext()) {
                                        val blog = gson.fromJson<BlogDto>(jsonReader, BlogDto::class.java)
                                        onBlog(blog)
                                        pageCount++
                                        totalCount++
                                    }
                                    jsonReader.endArray()
                                }
                            }
                        }
                    }

                    AppLogger.d(TAG, "Streamed $pageCount blogs, total so far: $totalCount")

                    // Check if we need more pages
                    hasMore = pageCount == PAGE_SIZE
                    offset += PAGE_SIZE
                }

                AppLogger.i(TAG, "Streaming sync complete: $totalCount blogs processed")
                Result.success(totalCount)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error streaming blogs", e)
                // Wrap network connectivity errors with user-friendly message
                // Check both direct exceptions and wrapped ones (e.g., SocketException wrapped in JsonSyntaxException)
                val isNetworkError = e is UnknownHostException || e is SocketException ||
                    e.cause is UnknownHostException || e.cause is SocketException ||
                    e.cause?.cause is SocketException
                
                val wrappedException = if (isNetworkError) {
                    AppLogger.w(TAG, "Network connectivity lost during sync")
                    NetworkInterruptedException("Network connection lost. Sync will resume when connection is restored.", e)
                } else {
                    e
                }
                Result.failure(wrappedException)
            }
        }
    }

    /**
     * Get the count of blogs matching sync criteria from server.
     * Uses Supabase count header to avoid fetching data.
     *
     * @param afterTimestamp Only count blogs created/updated after this timestamp
     * @return Result with count
     */
    suspend fun getServerCount(afterTimestamp: Long): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = BuildConfig.SYNC_BASE_URL
                val apiKey = BuildConfig.SYNC_API_KEY

                if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                    return@withContext Result.failure(Exception("Sync not configured"))
                }

                val url = "$baseUrl/rest/v1/blogs?select=id" +
                        "&or=(created_at.gte.$afterTimestamp,updated_at.gte.$afterTimestamp)"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "count=exact")
                    .addHeader("Range-Unit", "items")
                    .addHeader("Range", "0-0") // Only request 1 item to minimize data
                    .get()
                    .build()

                AppLogger.d(TAG, "Getting server count for sync")

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    AppLogger.e(TAG, "API error: ${response.code} - $errorBody")
                    return@withContext Result.failure(Exception("Failed to get count: ${response.code}"))
                }

                // Parse Content-Range header: "0-0/72000"
                val contentRange = response.header("Content-Range")
                val count = contentRange?.substringAfterLast("/")?.toIntOrNull() ?: 0

                AppLogger.i(TAG, "Server count: $count blogs to sync")
                Result.success(count)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error getting server count", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get all blog IDs from the server.
     * Used to detect direct deletions by comparing with local IDs.
     * 
     * @return Result containing set of all server blog IDs
     */
    suspend fun getAllBlogIds(): Result<Set<Long>> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = BuildConfig.SYNC_BASE_URL
                val apiKey = BuildConfig.SYNC_API_KEY

                if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                    return@withContext Result.failure(Exception("Sync not configured"))
                }

                val allIds = mutableSetOf<Long>()
                var offset = 0
                var hasMore = true

                while (hasMore) {
                    val url = "$baseUrl/rest/v1/blogs?select=id" +
                            "&order=id.asc" +
                            "&limit=$PAGE_SIZE" +
                            "&offset=$offset"

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .get()
                        .build()

                    AppLogger.d(TAG, "Fetching all blog IDs, offset $offset")

                    val response = client.newCall(request).execute()

                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val errorBody = resp.body?.string() ?: "Unknown error"
                            AppLogger.e(TAG, "API error fetching IDs: ${resp.code} - $errorBody")
                            return@withContext Result.failure(Exception("Failed to fetch IDs: ${resp.code}"))
                        }

                        val body = resp.body?.string() ?: "[]"
                        val idObjects = gson.fromJson(body, Array<DeletedIdDto>::class.java) ?: emptyArray()
                        val pageIds = idObjects.mapNotNull { it.id }
                        
                        allIds.addAll(pageIds)
                        
                        hasMore = pageIds.size == PAGE_SIZE
                        offset += PAGE_SIZE
                    }
                }

                AppLogger.i(TAG, "Fetched ${allIds.size} blog IDs from server")
                Result.success(allIds)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error fetching all blog IDs", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Stream blogs from Supabase starting after a specific blog ID.
     * Used for resume-on-kill: continues from where sync stopped.
     *
     * @param afterTimestamp Only fetch blogs created/updated after this timestamp
     * @param afterId Only fetch blogs with ID greater than this (for resume)
     * @param onBlog Callback invoked for each blog
     * @return Result with total count of blogs processed
     */
    suspend fun streamBlogsAfterId(
        afterTimestamp: Long,
        afterId: Long,
        onBlog: suspend (BlogDto) -> Unit
    ): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = BuildConfig.SYNC_BASE_URL
                val apiKey = BuildConfig.SYNC_API_KEY

                if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                    return@withContext Result.failure(Exception("Sync not configured"))
                }

                var totalCount = 0
                var currentAfterId = afterId
                var hasMore = true

                while (hasMore) {
                    // Use keyset pagination (id > afterId) instead of offset
                    val url = "$baseUrl/rest/v1/blogs?select=*" +
                            "&or=(created_at.gte.$afterTimestamp,updated_at.gte.$afterTimestamp)" +
                            "&id=gt.$currentAfterId" +
                            "&order=id.asc" +
                            "&limit=$PAGE_SIZE"

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Prefer", "return=representation")
                        .get()
                        .build()

                    AppLogger.d(TAG, "Streaming blogs after id $currentAfterId")

                    val response = client.newCall(request).execute()

                    // Track page count and lastId outside response block
                    var pageCount = 0
                    var lastId = currentAfterId
                    
                    // Use response.use{} to ensure connection is closed even on cancellation
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val errorBody = resp.body?.string() ?: "Unknown error"
                            AppLogger.e(TAG, "API error: ${resp.code} - $errorBody")
                            return@withContext Result.failure(Exception("Failed to fetch blogs: ${resp.code}"))
                        }

                        val body = resp.body
                        if (body == null) {
                            hasMore = false
                            return@use
                        }

                        // Stream parse the JSON array
                        body.byteStream().use { inputStream ->
                            InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
                                JsonReader(reader).use { jsonReader ->
                                    jsonReader.beginArray()
                                    while (jsonReader.hasNext()) {
                                        val blog = gson.fromJson<BlogDto>(jsonReader, BlogDto::class.java)
                                        onBlog(blog)
                                        pageCount++
                                        totalCount++
                                        blog.id?.let { lastId = it }
                                    }
                                    jsonReader.endArray()
                                }
                            }
                        }
                    }

                    AppLogger.d(TAG, "Streamed $pageCount blogs after id, total so far: $totalCount")

                    // Check if we need more pages
                    hasMore = pageCount == PAGE_SIZE
                    currentAfterId = lastId
                }

                AppLogger.i(TAG, "Streaming sync complete: $totalCount blogs processed (resumed from id $afterId)")
                Result.success(totalCount)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error streaming blogs after id", e)
                // Wrap network connectivity errors with user-friendly message
                // Check both direct exceptions and wrapped ones (e.g., SocketException wrapped in JsonSyntaxException)
                val isNetworkError = e is UnknownHostException || e is SocketException ||
                    e.cause is UnknownHostException || e.cause is SocketException ||
                    e.cause?.cause is SocketException
                
                val wrappedException = if (isNetworkError) {
                    AppLogger.w(TAG, "Network connectivity lost during sync")
                    NetworkInterruptedException("Network connection lost. Sync will resume when connection is restored.", e)
                } else {
                    e
                }
                Result.failure(wrappedException)
            }
        }
    }

    /**
     * Stream all blogs from Supabase with pagination.
     * Uses streaming JSON parsing to avoid loading entire response into memory.
     * 
     * @param onBlog Callback invoked for each blog - should insert to database
     * @return Result with total count of blogs processed
     */
    suspend fun streamAllBlogs(onBlog: suspend (BlogDto) -> Unit): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = BuildConfig.SYNC_BASE_URL
                val apiKey = BuildConfig.SYNC_API_KEY

                if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                    return@withContext Result.failure(Exception("Sync not configured"))
                }

                var totalCount = 0
                var offset = 0
                var hasMore = true

                while (hasMore) {
                    val url = "$baseUrl/rest/v1/blogs?select=*" +
                            "&order=id.asc" +
                            "&limit=$PAGE_SIZE" +
                            "&offset=$offset"

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .get()
                        .build()

                    AppLogger.d(TAG, "Streaming all blogs page at offset $offset")

                    val response = client.newCall(request).execute()

                    // Track page count outside response block for hasMore check
                    var pageCount = 0
                    
                    // Use response.use{} to ensure connection is closed even on cancellation
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val errorBody = resp.body?.string() ?: "Unknown error"
                            AppLogger.e(TAG, "API error: ${resp.code} - $errorBody")
                            return@withContext Result.failure(Exception("Failed to fetch blogs: ${resp.code}"))
                        }

                        val body = resp.body
                        if (body == null) {
                            hasMore = false
                            return@use
                        }

                        // Stream parse the JSON array
                        body.byteStream().use { inputStream ->
                            InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
                                JsonReader(reader).use { jsonReader ->
                                    jsonReader.beginArray()
                                    while (jsonReader.hasNext()) {
                                        val blog = gson.fromJson<BlogDto>(jsonReader, BlogDto::class.java)
                                        onBlog(blog)
                                        pageCount++
                                        totalCount++
                                    }
                                    jsonReader.endArray()
                                }
                            }
                        }
                    }

                    AppLogger.i(TAG, "Streamed page: $pageCount blogs, total: $totalCount")

                    hasMore = pageCount == PAGE_SIZE
                    offset += PAGE_SIZE
                }

                AppLogger.i(TAG, "Streaming all blogs complete: $totalCount total")
                Result.success(totalCount)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error streaming all blogs", e)
                // Wrap network connectivity errors with user-friendly message
                // Check both direct exceptions and wrapped ones (e.g., SocketException wrapped in JsonSyntaxException)
                val isNetworkError = e is UnknownHostException || e is SocketException ||
                    e.cause is UnknownHostException || e.cause is SocketException ||
                    e.cause?.cause is SocketException
                
                val wrappedException = if (isNetworkError) {
                    AppLogger.w(TAG, "Network connectivity lost during sync")
                    NetworkInterruptedException("Network connection lost. Sync will resume when connection is restored.", e)
                } else {
                    e
                }
                Result.failure(wrappedException)
            }
        }
    }

    /**
     * Get all blogs from Supabase with pagination.
     */
    suspend fun getAllBlogs(): Result<List<BlogDto>> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = BuildConfig.SYNC_BASE_URL
                val apiKey = BuildConfig.SYNC_API_KEY

                if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                    return@withContext Result.failure(Exception("Sync not configured"))
                }

                val allBlogs = mutableListOf<BlogDto>()
                var offset = 0
                var hasMore = true

                while (hasMore) {
                    val url = "$baseUrl/rest/v1/blogs?select=*" +
                            "&order=id.asc" +
                            "&limit=$PAGE_SIZE" +
                            "&offset=$offset"

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .get()
                        .build()

                    AppLogger.d(TAG, "Fetching all blogs page at offset $offset")

                    val response = client.newCall(request).execute()

                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        AppLogger.e(TAG, "API error: ${response.code} - $errorBody")
                        return@withContext Result.failure(Exception("Failed to fetch blogs: ${response.code}"))
                    }

                    val body = response.body?.string() ?: "[]"
                    val blogs = gson.fromJson(body, Array<BlogDto>::class.java).toList()
                    
                    allBlogs.addAll(blogs)
                    
                    // Check if we need to fetch more pages
                    hasMore = blogs.size == PAGE_SIZE
                    offset += PAGE_SIZE
                    
                    AppLogger.i(TAG, "Fetched page: ${blogs.size} blogs, total: ${allBlogs.size}")
                }
                
                AppLogger.i(TAG, "Downloaded ${allBlogs.size} total blogs from server")
                Result.success(allBlogs)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error fetching all blogs", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Upload/upsert a blog to Supabase.
     */
    suspend fun upsertBlog(blog: BlogDto): Result<BlogDto> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = BuildConfig.SYNC_BASE_URL
                val apiKey = BuildConfig.SYNC_API_KEY

                if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                    return@withContext Result.failure(Exception("Sync not configured"))
                }

                val url = "$baseUrl/rest/v1/blogs"
                val json = gson.toJson(blog)

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "resolution=merge-duplicates,return=representation")
                    .post(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                AppLogger.d(TAG, "Upserting blog: ${blog.title}")

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    AppLogger.e(TAG, "API error: ${response.code} - $errorBody")
                    return@withContext Result.failure(Exception("Failed to upload blog: ${response.code}"))
                }

                val body = response.body?.string() ?: "[]"
                val result = gson.fromJson(body, Array<BlogDto>::class.java).firstOrNull()
                    ?: return@withContext Result.failure(Exception("No result returned"))
                
                AppLogger.i(TAG, "Uploaded blog: ${result.title}")
                Result.success(result)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error upserting blog", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Upload multiple blogs to Supabase in batches.
     */
    suspend fun upsertBlogs(blogs: List<BlogDto>): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                if (blogs.isEmpty()) {
                    return@withContext Result.success(0)
                }

                val baseUrl = BuildConfig.SYNC_BASE_URL
                val apiKey = BuildConfig.SYNC_API_KEY

                if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                    return@withContext Result.failure(Exception("Sync not configured"))
                }

                var totalUploaded = 0
                
                // Upload in batches of 100 to avoid request size limits
                blogs.chunked(100).forEach { batch ->
                    val url = "$baseUrl/rest/v1/blogs"
                    val json = gson.toJson(batch)

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Prefer", "resolution=merge-duplicates")
                        .post(json.toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    AppLogger.d(TAG, "Upserting batch of ${batch.size} blogs")

                    val response = client.newCall(request).execute()

                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        AppLogger.e(TAG, "API error: ${response.code} - $errorBody")
                        // Continue with other batches even if one fails
                    } else {
                        totalUploaded += batch.size
                    }
                }

                AppLogger.i(TAG, "Uploaded $totalUploaded blogs successfully")
                Result.success(totalUploaded)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error upserting blogs", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Soft delete a blog on the server by setting is_deleted = true.
     * This replaces the old recycle_bin approach with simpler soft delete.
     * 
     * @param blogId The ID of the blog to soft delete
     * @return Result indicating success or failure
     */
    suspend fun softDeleteOnServer(blogId: Long): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = BuildConfig.SYNC_BASE_URL
                val apiKey = BuildConfig.SYNC_API_KEY

                if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                    return@withContext Result.failure(Exception("Sync not configured"))
                }

                val now = System.currentTimeMillis()
                val url = "$baseUrl/rest/v1/blogs?id=eq.$blogId"
                
                val updateData = mapOf(
                    "is_deleted" to true,
                    "deleted_at" to now,
                    "updated_at" to now
                )
                val json = gson.toJson(updateData)

                val request = Request.Builder()
                    .url(url)
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=representation") // Request updated record back
                    .patch(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                AppLogger.d(TAG, "Soft deleting blog on server: $blogId, URL: $url")

                val response = client.newCall(request).execute()

                response.use { resp ->
                    val responseBody = resp.body?.string()
                    AppLogger.d(TAG, "Soft delete response: ${resp.code} - $responseBody")
                    
                    if (!resp.isSuccessful) {
                        AppLogger.e(TAG, "Failed to soft delete on server: ${resp.code} - $responseBody")
                        return@withContext Result.failure(Exception("Failed to soft delete: ${resp.code}"))
                    }
                    
                    // Check if the response indicates any rows were updated
                    // Supabase returns an array - if empty [], no rows matched the filter
                    if (responseBody != null && responseBody.trim() == "[]") {
                        AppLogger.w(TAG, "Blog $blogId not found on server (empty response)")
                        // Still consider success - blog doesn't exist on server anyway
                    }
                }

                AppLogger.i(TAG, "Blog $blogId soft deleted on server")
                Result.success(Unit)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error soft deleting blog on server", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Get IDs of blogs that have been soft-deleted on the server.
     * Queries blogs table for is_deleted = true.
     * 
     * @param afterTimestamp Only get deletions that occurred after this timestamp
     * @return Result containing list of deleted blog IDs
     */
    suspend fun getDeletedBlogIds(afterTimestamp: Long): Result<List<Long>> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = BuildConfig.SYNC_BASE_URL
                val apiKey = BuildConfig.SYNC_API_KEY

                if (baseUrl.isEmpty() || apiKey.isEmpty()) {
                    return@withContext Result.failure(Exception("Sync not configured"))
                }

                val allIds = mutableListOf<Long>()
                var offset = 0
                var hasMore = true

                while (hasMore) {
                    // Query blogs for soft-deleted items after timestamp
                    val url = "$baseUrl/rest/v1/blogs?select=id" +
                            "&is_deleted=eq.true" +
                            "&updated_at=gte.$afterTimestamp" +
                            "&order=id.asc" +
                            "&limit=$PAGE_SIZE" +
                            "&offset=$offset"

                    val request = Request.Builder()
                        .url(url)
                        .addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .get()
                        .build()

                    AppLogger.d(TAG, "Fetching soft-deleted IDs, offset $offset")

                    val response = client.newCall(request).execute()

                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val errorBody = resp.body?.string() ?: "Unknown error"
                            AppLogger.e(TAG, "API error fetching deleted IDs: ${resp.code} - $errorBody")
                            // Don't fail sync for this, just return empty list
                            return@withContext Result.success(emptyList())
                        }

                        val body = resp.body?.string() ?: "[]"
                        
                        // Parse the JSON array of {id: Long} objects
                        val idObjects = gson.fromJson(body, Array<DeletedIdDto>::class.java) ?: emptyArray()
                        val pageIds = idObjects.mapNotNull { it.id }
                        
                        allIds.addAll(pageIds)
                        
                        hasMore = pageIds.size == PAGE_SIZE
                        offset += PAGE_SIZE
                    }
                }

                AppLogger.i(TAG, "Found ${allIds.size} soft-deleted blog IDs from server")
                Result.success(allIds)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error fetching deleted blog IDs", e)
                // Don't fail sync for this, just return empty list
                Result.success(emptyList())
            }
        }
    }
}
