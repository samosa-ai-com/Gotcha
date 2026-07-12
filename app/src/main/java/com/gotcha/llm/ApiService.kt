package com.gotcha.llm

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface ApiService {
    @POST("chat/completions")
    suspend fun chat(
        @Body request: ChatRequest,
        // Passed per-request; Retrofit omits the header entirely when null.
        @Header("X-Session-Id") sessionId: String? = null
    ): ChatResponse
}
