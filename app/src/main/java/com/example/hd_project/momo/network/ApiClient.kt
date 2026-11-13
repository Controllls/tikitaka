package com.example.hd_project.momo.network

import android.content.Context
import com.example.hd_project.BuildConfig
import com.example.hd_project.momo.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class Tokens(val accessToken: String, val refreshToken: String, val tokenType: String, val expiresIn: Int)
@Serializable data class DeleteResult(val jobId: String, val deletionRequested: Boolean)
@Serializable data class BackendHealth(val status: String)
@Serializable data class HighlightOptions(val consentVersion: String = "automatic-highlights-v1")
@Serializable data class HighlightClip(val number: Int, val title: String, val startMs: Long, val endMs: Long,
    val score: Double, val sha256: String, val size: Long)
@Serializable data class HighlightResult(val schemaVersion: String, val jobId: String, val audioSha256: String,
    val durationMs: Long, val threshold: Double, val dataOrigin: String, val clips: List<HighlightClip>)

class AnalysisServerUnavailable(message: String): IOException(message)
class InstallationAuthenticationException(val statusCode: Int, message: String): IOException(message) {
    val permanent get() = statusCode in listOf(400, 401, 403, 404, 422)
}

interface AnalysisApi {
    @GET("v1/episodes/profile") suspend fun episodeProfile(): kotlinx.serialization.json.JsonObject
    @Multipart @POST("v1/episodes") suspend fun submitEpisode(@Header("Idempotency-Key") key: String,
        @Part audio: MultipartBody.Part, @Part("sha256") sha256: RequestBody, @Part("options") options: RequestBody): JobStatus
    @GET("v1/episodes/{id}") suspend fun episodeStatus(@Path("id") id: String): JobStatus
    @GET("v1/episodes/{id}/result") suspend fun episodeResult(@Path("id") id: String): kotlinx.serialization.json.JsonObject
    @POST("v1/episodes/{id}/render") suspend fun renderEpisode(@Path("id") id: String, @Header("Idempotency-Key") key: String,
        @Body cast: kotlinx.serialization.json.JsonObject): JobStatus
    @Streaming @GET("v1/episodes/{id}/files/{name}") suspend fun episodeFile(@Path("id") id: String, @Path("name") name: String): ResponseBody
    @Multipart @POST("v1/highlights") suspend fun submitHighlights(@Header("Idempotency-Key") key: String,
        @Part audio: MultipartBody.Part, @Part("sha256") sha256: RequestBody, @Part("options") options: RequestBody): JobStatus
    @GET("v1/highlights/{id}") suspend fun highlightStatus(@Path("id") id: String): JobStatus
    @GET("v1/highlights/{id}/result") suspend fun highlightResult(@Path("id") id: String): HighlightResult
    @Streaming @GET("v1/highlights/{id}/files/{name}") suspend fun highlightFile(@Path("id") id: String, @Path("name") name: String): ResponseBody
    @GET("health/ready") suspend fun health(): retrofit2.Response<BackendHealth>
    @POST("v1/auth/logout") suspend fun logout(@Body request: RefreshRequest): retrofit2.Response<Unit>
    @Multipart @POST("v1/analyses") suspend fun submit(@Header("Idempotency-Key") key: String,
        @Part audio: MultipartBody.Part, @Part("sha256") sha256: RequestBody, @Part("options") options: RequestBody): JobStatus
    @GET("v1/analyses") suspend fun list(@Query("cursor") cursor: String? = null): JobPage
    @GET("v1/analyses/{id}") suspend fun status(@Path("id") id: String): JobStatus
    @GET("v1/analyses/{id}/result") suspend fun result(@Path("id") id: String): AnalysisResult
    @GET("v1/analyses/{id}/tracks") suspend fun tracks(@Path("id") id: String): MotionTracks
    @POST("v1/analyses/{id}/cancel") suspend fun cancel(@Path("id") id: String): JobStatus
    @POST("v1/analyses/{id}/retry") suspend fun retry(@Path("id") id: String, @Header("Idempotency-Key") key: String): JobStatus
    @DELETE("v1/analyses/{id}") suspend fun delete(@Path("id") id: String): DeleteResult
}

class ApiClient(context: Context, inputUrl: String) {
    val baseUrl: String = inputUrl.trim().trimEnd('/') + "/"
    private val installation = InstallationStore(context, baseUrl)
    val installationId: String get() = installation.read().credentials.installationId
    init {
        val uri = android.net.Uri.parse(baseUrl)
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "서버 주소만 입력해 주세요." }
        require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http" && uri.host in listOf("10.0.2.2", "localhost", "127.0.0.1"))) { "HTTPS 서버 주소가 필요합니다." }
    }
    private val refreshClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val client = refreshClient.newBuilder().writeTimeout(5, TimeUnit.MINUTES).callTimeout(0, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            if(chain.request().method=="POST" && listOf("/v1/analyses","/v1/highlights","/v1/episodes").any {
                    chain.request().url.encodedPath.trimEnd('/').endsWith(it) })
                throw IOException("이 앱에서는 원음 전송을 사용하지 않습니다. 기기 분석으로 다시 제작해 주세요.")
            val request = chain.request().newBuilder()
            if (protectedPath(chain.request().url.encodedPath)) {
                request.header("Authorization", "Bearer ${authenticate().accessToken}")
            }
            chain.proceed(request.build())
        }.authenticator { _, response ->
            if (!protectedPath(response.request.url.encodedPath) || response.priorResponse != null) null
            else {
                val next = authenticate(response.request.header("Authorization"))
                response.request.newBuilder().header("Authorization", "Bearer ${next.accessToken}").build()
            }
        }.build()
    val api: AnalysisApi = Retrofit.Builder().baseUrl(baseUrl).client(client)
        .addConverterFactory(MomoJson.asConverterFactory("application/json".toMediaType())).build().create(AnalysisApi::class.java)

    suspend fun ensureAuthenticated() = withContext(Dispatchers.IO) { authenticate(); Unit }

    private fun protectedPath(path: String) = path.contains("/v1/analyses") || path.contains("/v1/highlights") || path.contains("/v1/episodes")

    private fun authenticate(rejectedAuthorization: String? = null): Tokens = synchronized(installation.lock) {
        val state = installation.read()
        val current = state.tokens
        if (current != null && (rejectedAuthorization == null || rejectedAuthorization != "Bearer ${current.accessToken}")) return@synchronized current
        if (current != null) {
            try {
                val next = requestTokens("refresh", MomoJson.encodeToString(RefreshRequest(current.refreshToken)))
                installation.saveTokens(next)
                return@synchronized next
            } catch (e: InstallationAuthenticationException) {
                if (e.statusCode != 401) throw e
                installation.saveTokens(null)
            }
        }
        val next = requestTokens("installation", MomoJson.encodeToString(state.credentials))
        installation.saveTokens(next)
        next
    }

    private fun requestTokens(route: String, json: String): Tokens {
        val request = Request.Builder().url(baseUrl + "v1/auth/" + route)
            .post(json.toRequestBody("application/json".toMediaType())).build()
        return refreshClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw InstallationAuthenticationException(response.code, when (response.code) {
                403 -> "이 앱의 서버 이용이 중지되었어요."
                404 -> "서버가 자동 인증을 지원하지 않아요. 분석 서버를 업데이트해 주세요."
                429 -> "연결 요청이 많아요. 잠시 후 다시 시도해 주세요."
                else -> "앱을 서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요."
            })
            try { MomoJson.decodeFromString<Tokens>(response.body!!.string()) }
            catch (e: kotlinx.serialization.SerializationException) { throw IOException("서버 인증 응답을 확인하지 못했어요.", e) }
        }
    }

    /** Called only for new server work; cached analysis remains usable offline. */
    suspend fun requireAnalysisReady() {
        val response = try {
            withTimeout(8_000) { api.health() }
        } catch (_: TimeoutCancellationException) {
            throw AnalysisServerUnavailable("분석 서버 응답이 늦어요. 서버 실행 상태와 네트워크를 확인해 주세요.")
        } catch (_: IOException) {
            throw AnalysisServerUnavailable("분석 서버에 연결할 수 없어요. 서버 실행 상태와 네트워크를 확인해 주세요.")
        }
        if (response.code() == 503) {
            throw AnalysisServerUnavailable("분석 서버가 아직 준비되지 않았어요. 잠시 후 다시 시도해 주세요.")
        }
        if (!response.isSuccessful || response.body()?.status != "ready") {
            throw AnalysisServerUnavailable("분석 서버 상태를 확인하지 못했어요. 서버 주소를 확인해 주세요.")
        }
    }
}
