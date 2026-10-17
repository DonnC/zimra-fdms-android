package org.donnc.fzimra.example.fiscal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.donnc.fzimra.core.FdmsNetworkException
import org.donnc.fzimra.core.FdmsRequest
import org.donnc.fzimra.core.FdmsResponse
import org.donnc.fzimra.core.FdmsTransport
import java.time.LocalTime

/** One request to the gateway and what came back. */
data class Exchange(
    val id: Long,
    val at: LocalTime,
    val method: String,
    val endpoint: String,
    val request: String?,
    val status: Int?,
    val response: String?,
    val error: String?,
    val millis: Long,
)

/** Keeps the latest exchanges with the gateway, for the Traffic screen. */
class TrafficLog(private val capacity: Int = 200) {
    private val entries = MutableStateFlow<List<Exchange>>(emptyList())
    val exchanges: StateFlow<List<Exchange>> = entries
    private var nextId = 0L

    fun clear() = entries.update { emptyList() }

    /** [transport], with every exchange recorded here. */
    fun around(transport: FdmsTransport): FdmsTransport = FdmsTransport { request ->
        val started = System.nanoTime()
        try {
            transport.execute(request).also { record(request, it, null, started) }
        } catch (e: FdmsNetworkException) {
            record(request, null, e.message ?: "network error", started)
            throw e
        }
    }

    private fun record(request: FdmsRequest, response: FdmsResponse?, error: String?, started: Long) {
        val exchange = synchronized(this) {
            Exchange(
                id = nextId++,
                at = LocalTime.now(),
                method = request.method,
                endpoint = request.url.substringAfter("/v1/").substringBefore('?').let { path ->
                    if (path.contains('/')) path.substringAfter('/') else path
                },
                request = request.body,
                status = response?.status,
                response = response?.body,
                error = error,
                millis = (System.nanoTime() - started) / 1_000_000,
            )
        }
        entries.update { (listOf(exchange) + it).take(capacity) }
    }
}
