package com.example.chargecontrol.network

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class DynamicHostInterceptorTest {

    private class FakeChain(private val initialRequest: Request) : Interceptor.Chain {
        var proceededRequest: Request? = null

        override fun request(): Request = initialRequest

        override fun proceed(request: Request): Response {
            proceededRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException("not needed for this test")
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    @Test
    fun `rewrites host and port while preserving path and query`() {
        val interceptor = DynamicHostInterceptor(
            hostProvider = { "192.168.1.50" },
            portProvider = { 9999 }
        )
        val request = Request.Builder()
            .url("http://chargecontrol.invalid:7070/api/state?limit=10")
            .build()
        val chain = FakeChain(request)

        interceptor.intercept(chain)

        val rewritten = chain.proceededRequest!!.url
        assertEquals("192.168.1.50", rewritten.host)
        assertEquals(9999, rewritten.port)
        assertEquals("/api/state", rewritten.encodedPath)
        assertEquals("limit=10", rewritten.encodedQuery)
    }

    @Test
    fun `reads the provider fresh on every call, no caching`() {
        var currentHost = "10.0.0.1"
        val interceptor = DynamicHostInterceptor(
            hostProvider = { currentHost },
            portProvider = { 80 }
        )
        val request = Request.Builder().url("http://chargecontrol.invalid/").build()

        val firstChain = FakeChain(request)
        interceptor.intercept(firstChain)
        assertEquals("10.0.0.1", firstChain.proceededRequest!!.url.host)

        currentHost = "10.0.0.2"
        val secondChain = FakeChain(request)
        interceptor.intercept(secondChain)
        assertEquals("10.0.0.2", secondChain.proceededRequest!!.url.host)
    }
}
