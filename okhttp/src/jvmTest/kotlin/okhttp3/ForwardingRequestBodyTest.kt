package okhttp3

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.lang.reflect.Modifier
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

open class ForwardingRequestBodyTest {
  /**
   * Verifies that overriding the content type preserves the delegate's content,
   * content length, and one-shot and duplex flags.
   */
  @ParameterizedTest
  @CsvSource(
    "false, false",
    "false, true",
    "true, false",
    "true, true",
  )
  fun overridingContentTypePreservesBodyBehavior(
    oneShot: Boolean,
    duplex: Boolean,
  ) {
    val originalType = "text/plain".toMediaType()
    val overriddenType = "application/octet-stream".toMediaType()

    val delegate =
      object : RequestBody() {
        override fun contentType(): MediaType = originalType

        override fun contentLength(): Long = 5L

        override fun writeTo(sink: BufferedSink) {
          sink.writeUtf8("hello")
        }

        override fun isOneShot(): Boolean = oneShot

        override fun isDuplex(): Boolean = duplex
      }

    val forwardingBody =
      object : ForwardingRequestBody(delegate) {
        override fun contentType(): MediaType = overriddenType
      }

    assertThat(forwardingBody.contentType()).isEqualTo(overriddenType)
    assertThat(delegate.contentType()).isEqualTo(originalType)
    assertThat(forwardingBody.contentLength()).isEqualTo(5L)
    assertThat(forwardingBody.isOneShot()).isEqualTo(oneShot)
    assertThat(forwardingBody.isDuplex()).isEqualTo(duplex)

    val sink = Buffer()
    forwardingBody.writeTo(sink)
    assertThat(sink.readUtf8()).isEqualTo("hello")
  }

  /**
   * Verifies that the delegate's content type is forwarded, including null
   * when the delegate does not specify one.
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = ["text/plain"])
  fun forwardsContentType(contentType: String?) {
    val mediaType = contentType?.toMediaType()
    val delegate = byteArrayOf(1, 2, 3).toRequestBody(mediaType)
    val forwardingBody = ForwardingRequestBody(delegate)

    assertThat(forwardingBody.contentType()).isEqualTo(mediaType)
  }

  @Test
  fun overridesAllOverridableRequestBodyMethods() {
    for (method in RequestBody::class.java.declaredMethods) {
      val modifiers = method.modifiers
      if (!Modifier.isPublic(modifiers) ||
        Modifier.isStatic(modifiers) ||
        Modifier.isFinal(modifiers) ||
        method.isSynthetic
      ) {
        continue
      }

      ForwardingRequestBody::class.java.getDeclaredMethod(
        method.name,
        *method.parameterTypes,
      )
    }
  }
}
