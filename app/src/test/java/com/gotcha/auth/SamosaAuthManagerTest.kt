package com.gotcha.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gotcha.data.SettingsRepository
import com.gotcha.testsupport.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SamosaAuthManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setUp()
        settingsRepository = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        settingsRepository.saveSamosaSession("", "")
    }

    private class FakeSamosaAuthApi(
        var meResponse: MeResponse = MeResponse(),
        var claimResponse: ClaimReferralResponse = ClaimReferralResponse(),
        var claimHttpError: HttpException? = null,
        var registerResponse: RegisterResponse = RegisterResponse()
    ) : SamosaAuthApi {
        var lastRegisterRequest: RegisterRequest? = null
        var lastClaimBearer: String? = null
        var lastClaimRequest: ClaimReferralRequest? = null
        var lastMeBearer: String? = null

        override suspend fun register(body: RegisterRequest): RegisterResponse {
            lastRegisterRequest = body
            return registerResponse
        }

        override suspend fun me(bearer: String): MeResponse {
            lastMeBearer = bearer
            return meResponse
        }

        override suspend fun claimReferral(bearer: String, body: ClaimReferralRequest): ClaimReferralResponse {
            lastClaimBearer = bearer
            lastClaimRequest = body
            claimHttpError?.let { throw it }
            return claimResponse
        }

        override suspend fun logout(bearer: String) {}
    }

    @Test
    fun `fetchUserProfile returns profile when session token is present`() = runBlocking {
        val fakeUser = SamosaUser(
            id = "user-123",
            email = "test@example.com",
            tier = SamosaTier("pro", "Pro", "#0d6efd"),
            tags = listOf("vip"),
            referral = SamosaReferral(code = "AIR-ABCDEF", totalReferred = 2, canClaim = true)
        )
        val fakeApi = FakeSamosaAuthApi(meResponse = MeResponse(fakeUser))
        settingsRepository.saveSamosaSession("valid-jwt", "test@example.com")

        val manager = SamosaAuthManager(context, settingsRepository, fakeApi)
        val profile = manager.fetchUserProfile()

        assertNotNull(profile)
        assertEquals("user-123", profile?.id)
        assertEquals("Pro", profile?.tier?.displayName)
        assertEquals(listOf("vip"), profile?.tags)
        assertEquals("AIR-ABCDEF", profile?.referral?.code)
        assertEquals("Bearer valid-jwt", fakeApi.lastMeBearer)
    }

    @Test
    fun `fetchUserProfile returns null when session token is blank`() = runBlocking {
        settingsRepository.clearSamosaSession()
        val fakeApi = FakeSamosaAuthApi()
        val manager = SamosaAuthManager(context, settingsRepository, fakeApi)
        val profile = manager.fetchUserProfile()

        assertNull(profile)
        assertNull(fakeApi.lastMeBearer)
    }

    @Test
    fun `claimReferralCode succeeds and passes normalized uppercase code`() = runBlocking {
        settingsRepository.saveSamosaSession("valid-jwt", "test@example.com")
        val fakeClaimResp = ClaimReferralResponse(
            message = "Referral claimed",
            referral = ClaimedReferralInfo(code = "AIR-K9X2P7", refereeReward = 50.0, referrerReward = 50.0),
            creditsRemaining = 1300.0
        )
        val fakeApi = FakeSamosaAuthApi(claimResponse = fakeClaimResp)
        val manager = SamosaAuthManager(context, settingsRepository, fakeApi)

        val result = manager.claimReferralCode("  air-k9x2p7  ")

        assertTrue(result.isSuccess)
        assertEquals("Referral claimed", result.getOrNull()?.message)
        assertEquals("AIR-K9X2P7", fakeApi.lastClaimRequest?.referralCode)
        assertEquals("Bearer valid-jwt", fakeApi.lastClaimBearer)
    }

    @Test
    fun `claimReferralCode maps 400 self-referral error`() = runBlocking {
        settingsRepository.saveSamosaSession("valid-jwt", "test@example.com")
        val errorBody = "{\"detail\":\"cannot refer yourself\"}".toResponseBody(null)
        val fakeApi = FakeSamosaAuthApi(
            claimHttpError = HttpException(Response.error<String>(400, errorBody))
        )
        val manager = SamosaAuthManager(context, settingsRepository, fakeApi)

        val result = manager.claimReferralCode("AIR-MYCODE")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("cannot refer yourself", ignoreCase = true) == true)
    }

    @Test
    fun `claimReferralCode maps 404 not found error`() = runBlocking {
        settingsRepository.saveSamosaSession("valid-jwt", "test@example.com")
        val errorBody = "{\"detail\":\"referral code not found\"}".toResponseBody(null)
        val fakeApi = FakeSamosaAuthApi(
            claimHttpError = HttpException(Response.error<String>(404, errorBody))
        )
        val manager = SamosaAuthManager(context, settingsRepository, fakeApi)

        val result = manager.claimReferralCode("AIR-UNKNOWN")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not found", ignoreCase = true) == true)
    }

    @Test
    fun `claimReferralCode maps 409 referrer reward limit reached`() = runBlocking {
        settingsRepository.saveSamosaSession("valid-jwt", "test@example.com")
        val errorBody = "{\"detail\":\"referrer reward limit reached\"}".toResponseBody(null)
        val fakeApi = FakeSamosaAuthApi(
            claimHttpError = HttpException(Response.error<String>(409, errorBody))
        )
        val manager = SamosaAuthManager(context, settingsRepository, fakeApi)

        val result = manager.claimReferralCode("AIR-MAXED")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("limit", ignoreCase = true) == true)
    }

    @Test
    fun `claimReferralCode maps 410 window expired`() = runBlocking {
        settingsRepository.saveSamosaSession("valid-jwt", "test@example.com")
        val errorBody = "{\"detail\":\"referral window expired\"}".toResponseBody(null)
        val fakeApi = FakeSamosaAuthApi(
            claimHttpError = HttpException(Response.error<String>(410, errorBody))
        )
        val manager = SamosaAuthManager(context, settingsRepository, fakeApi)

        val result = manager.claimReferralCode("AIR-EXPIRED")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("expired", ignoreCase = true) == true)
    }
}
