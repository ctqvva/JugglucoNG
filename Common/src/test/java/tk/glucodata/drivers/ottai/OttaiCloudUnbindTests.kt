package tk.glucodata.drivers.ottai

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OttaiCloudUnbindTests {
    @Test
    fun tokenRejectionSurvivesSuccessfulRefreshBeforeUnbindReturns() {
        val response = request(
            401,
            """{"code":"AuthFailed_TokenInvalid","message":"expired session"}""",
            onDisconnect = { request(200, """{"code":200}""") },
        )
        // Simulate the refresh completing after unbind captures its response, before the UI
        // receives the result. The shared diagnostic has already been cleared.
        assertNull(OttaiCloudClient.lastFailure)
        val result = OttaiCloudClient.unbindResponseResult(response)
        assertFalse(result.released)
        val failure = result.failure!!
        assertTrue(failure.isTokenInvalid)
        assertEquals("http=401 biz=AuthFailed_TokenInvalid expired session", failure.text)
    }

    @Test
    fun refreshFailureCannotReplaceUnbindFailureDetails() {
        val response = request(
            200,
            """{"code":"UnbindRejected","message":"binding could not be released"}""",
            onDisconnect = { request(503, """{"code":"ListUnavailable","message":"refresh failed"}""") },
        )
        assertEquals("ListUnavailable", OttaiCloudClient.lastFailure?.code)
        val result = OttaiCloudClient.unbindResponseResult(response)
        assertFalse(result.released)
        assertEquals("UnbindRejected", result.failure?.code)
        assertEquals("http=200 biz=UnbindRejected binding could not be released", result.failure?.text)
    }

    @Test
    fun networkFailureSurvivesAnotherRequestClearingTheDiagnostic() {
        val response = request(
            0,
            "",
            networkError = IOException("connection lost"),
            onDisconnect = { request(200, """{"code":200}""") },
        )
        assertNull(OttaiCloudClient.lastFailure)
        val result = OttaiCloudClient.unbindResponseResult(response)
        assertFalse(result.released)
        assertEquals("network: connection lost", result.failure?.text)
    }

    @Test
    fun successfulUnbindDoesNotInheritAnotherRequestsFailure() {
        val response = request(
            200,
            """{"code":"OK"}""",
            onDisconnect = { request(401, """{"code":"AuthFailed_TokenInvalid"}""") },
        )
        assertTrue(OttaiCloudClient.lastFailure!!.isTokenInvalid)
        val result = OttaiCloudClient.unbindResponseResult(response)
        assertTrue(result.released)
        assertNull(result.failure)
    }

    @Test
    fun alreadyEndedSensorStillCountsAsReleased() {
        val result = OttaiCloudClient.unbindResponseResult(
            request(200, """{"code":"AppDevice_EndUsing"}"""),
        )
        assertTrue(result.released)
        assertNull(result.failure)
    }

    @Test
    fun failedHttpStatusWithoutBusinessCodeDoesNotCountAsReleased() {
        val result = OttaiCloudClient.unbindResponseResult(request(503, "{}"))
        assertFalse(result.released)
        assertEquals("http=503 biz=", result.failure?.text)
    }

    private fun request(
        status: Int,
        json: String,
        networkError: IOException? = null,
        onDisconnect: () -> Unit = {},
    ): OttaiCloudClient.CloudRequestResult = OttaiCloudClient.requestWithResult(
        method = "PUT",
        url = "https://example.invalid/deviceBind/unBindDevice",
        body = "{}",
        headers = emptyMap(),
        openConnection = { url ->
            object : HttpURLConnection(URL(url)) {
                override fun connect() = Unit
                override fun usingProxy() = false
                override fun disconnect() = onDisconnect()
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun getResponseCode(): Int {
                    if (networkError != null) throw networkError
                    return status
                }
                override fun getInputStream() = ByteArrayInputStream(json.toByteArray())
                override fun getErrorStream() = ByteArrayInputStream(json.toByteArray())
            }
        },
    )
}
