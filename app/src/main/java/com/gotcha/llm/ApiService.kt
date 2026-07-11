package com.gotcha.llm

import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("chat/completions")
    suspend fun chat(@Body request: ChatRequest): ChatResponse
}
