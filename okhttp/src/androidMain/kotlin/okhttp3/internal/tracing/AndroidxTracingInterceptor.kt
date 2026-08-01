package okhttp3.internal.tracing

import androidx.tracing.trace
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Tracing implementation of Interceptor that marks each Call in a Perfetto
 * trace. Typically used as a network interceptor.
 */
internal class AndroidxTracingInterceptor(
    val traceLabel: (Request) -> String = { it.defaultTracingLabel },
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response =
    trace(traceLabel(chain.request()).take(MAX_TRACE_LABEL_LENGTH)) {
      chain.proceed(chain.request())
    }

  companion object {
    internal const val MAX_TRACE_LABEL_LENGTH = 127

    val Request.defaultTracingLabel: String
      get() {
        return url.encodedPath
      }
  }
}
