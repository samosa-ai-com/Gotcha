package com.gotcha.llm

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface ApiService {
    @POST("chat/completions")
    suspend fun chat(
        @Body request: ChatRequest,
        @Header("X-Session-Id") sessionId: String? = null
    ): ChatResponse

    @retrofit2.http.GET("models")
    suspend fun listModels(): ModelListResponse
}
