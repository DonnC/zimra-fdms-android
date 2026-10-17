package org.donnc.fzimra.example.fiscal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.donnc.fzimra.core.FdmsObserver
import org.donnc.fzimra.core.FdmsRequest
import org.donnc.fzimra.core.FdmsResponse
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong

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

/** Keeps the latest exchanges with the gateway, for the Traffic screen. Handed to the terminal as its observer. */
class TrafficLog(private val capacity: Int = 200) : FdmsObserver {
    private val entries = MutableStateFlow<List<Exchange>>(emptyList())
    val exchanges: StateFlow<List<Exchange>> = entries
    private val ids = AtomicLong()

    fun clear() = entries.update { emptyList() }

    override fun onExchange(request: FdmsRequest, response: FdmsResponse?, error: Exception?, millis: Long) {
        val path = request.url.substringAfter("/v1/").substringBefore('?')
        val exchange = Exchange(
            id = ids.incrementAndGet(),
            at = LocalTime.now(),
            method = request.method,
            endpoint = if (path.contains('/')) path.substringAfter('/') else path,
            request = request.body,
            status = response?.status,
            response = response?.body,
            error = error?.message,
            millis = millis,
        )
        entries.update { (listOf(exchange) + it).take(capacity) }
    }
}
