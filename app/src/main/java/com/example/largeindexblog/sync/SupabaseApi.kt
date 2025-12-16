package com.example.largeindexblog.sync

import com.example.largeindexblog.BuildConfig
import com.example.largeindexblog.data.entity.BlogEntity
import com.example.largeindexblog.util.AppLogger
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
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
    @SerializedName("title_prefix") val titlePrefix: String,
    @SerializedName("created_at") val createdAt: Long,
    @SerializedName("updated_at") val updatedAt: Long,
    @SerializedName("device_id") val deviceId: String
) {
    fun toBlogEntity(): BlogEntity {
        return BlogEntity(
            id = id ?: 0,
            title = title,
            content = content,
            titlePrefix = titlePrefix,
            createdAt = createdAt,
            updatedAt = updatedAt,
            deviceId = deviceId
        )
    }

    companion object {
        fun fromBlogEntity(entity: BlogEntity): BlogDto {
            return BlogDto(
                id = entity.id,
                title = entity.title,
                content = entity.content,
                titlePrefix = entity.titlePrefix,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                deviceId = entity.deviceId
            )
        }
    }
}

/**
 * Supabase REST API client for blog sync.
 * Handles pagination for large datasets.
 */
@Singleton
class SupabaseApi @Inject constructor() {

    companion object {
        private const val TAG = "SupabaseApi"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val PAGE_SIZE = 1000 // Supabase default limit
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
     * Get blogs from Supabase that have been updated after the given timestamp.
     * Uses pagination to fetch all matching records.
     */
    suspend fun getBlogsAfter(afterTimestamp: Long): Result<List<BlogDto>> {
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
                    // Supabase REST API query with pagination
                    val url = "$baseUrl/rest/v1/blogs?select=*" +
                            "&or=(created_at.gt.$afterTimestamp,updated_at.gt.$afterTimestamp)" +
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

                    AppLogger.d(TAG, "Fetching blogs page at offset $offset")

                    val response = client.newCall(request).execute()

                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        AppLogger.e(TAG, "API error: ${response.code} - $errorBody")
                        return@withContext Result.failure(Exception("Failed to fetch blogs: ${response.code}"))
                    }

                    val body = response.body?.string() ?: "[]"
                    val blogs = gson.fromJson(body, Array<BlogDto>::class.java).toList()
                    
                    allBlogs.addAll(blogs)
                    
                    // Check if we need to fetch more
                    hasMore = blogs.size == PAGE_SIZE
                    offset += PAGE_SIZE
                    
                    AppLogger.d(TAG, "Fetched ${blogs.size} blogs, total so far: ${allBlogs.size}")
                }
                
                AppLogger.i(TAG, "Downloaded ${allBlogs.size} blogs from server")
                Result.success(allBlogs)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error fetching blogs", e)
                Result.failure(e)
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
}
