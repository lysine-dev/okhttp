/*
 * Copyright (C) 2012 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import okhttp3.internal.http.parseChallenges
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class HeadersChallengesTest {
  /** See https://github.com/lysine-dev/okhttp/issues/2780.  */
  @Test fun testDigestChallengeWithStrictRfc2617Header() {
    val headers =
      Headers
        .Builder()
        .add(
          "WWW-Authenticate",
          "Digest realm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaks" +
            "jdflkasdf\", qop=\"auth\", stale=\"FALSE\"",
        ).build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithDifferentlyOrderedAuthParams() {
    val headers =
      Headers
        .Builder()
        .add(
          "WWW-Authenticate",
          "Digest qop=\"auth\", realm=\"myrealm\", nonce=\"fjalskdflwejrlask" +
            "dfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"",
        ).build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithDifferentlyOrderedAuthParams2() {
    val headers =
      Headers
        .Builder()
        .add(
          "WWW-Authenticate",
          "Digest qop=\"auth\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaksjdflk" +
            "asdf\", realm=\"myrealm\", stale=\"FALSE\"",
        ).build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithMissingRealm() {
    val headers =
      Headers
        .Builder()
        .add(
          "WWW-Authenticate",
          "Digest qop=\"auth\", underrealm=\"myrealm\", nonce=\"fjalskdflwej" +
            "rlaskdfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"",
        ).build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isNull()
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["underrealm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithAdditionalSpaces() {
    val headers =
      Headers
        .Builder()
        .add(
          "WWW-Authenticate",
          "Digest qop=\"auth\",    realm=\"myrealm\", nonce=\"fjalskdflwejrl" +
            "askdfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"",
        ).build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithAdditionalSpacesBeforeFirstAuthParam() {
    val headers =
      Headers
        .Builder()
        .add(
          "WWW-Authenticate",
          "Digest    realm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjfl" +
            "aksjdflkasdf\", qop=\"auth\", stale=\"FALSE\"",
        ).build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithCamelCasedNames() {
    val headers =
      Headers
        .Builder()
        .add(
          "WWW-Authenticate",
          "DiGeSt qop=\"auth\", rEaLm=\"myrealm\", nonce=\"fjalskdflwejrlask" +
            "dfjlaskdjflaksjdflkasdf\", stale=\"FALSE\"",
        ).build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("DiGeSt")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithCamelCasedNames2() {
    // Strict RFC 2617 camelcased.
    val headers =
      Headers
        .Builder()
        .add(
          "WWW-Authenticate",
          "DIgEsT rEaLm=\"myrealm\", nonce=\"fjalskdflwejrlaskdfjlaskdjflaks" +
            "jdflkasdf\", qop=\"auth\", stale=\"FALSE\"",
        ).build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("DIgEsT")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    val expectedAuthParams = mutableMapOf<String, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["nonce"] = "fjalskdflwejrlaskdfjlaskdjflaksjdflkasdf"
    expectedAuthParams["qop"] = "auth"
    expectedAuthParams["stale"] = "FALSE"
    assertThat(challenges[0].authParams).isEqualTo(expectedAuthParams)
  }

  @Test fun testDigestChallengeWithTokenFormOfAuthParam() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Digest realm=myrealm")
        .build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isEqualTo("myrealm")
    assertThat(challenges[0].authParams)
      .isEqualTo(mapOf("realm" to "myrealm"))
  }

  @Test fun testDigestChallengeWithoutAuthParams() {
    // Scheme only.
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Digest")
        .build()
    val challenges = headers.parseChallenges("WWW-Authenticate")
    assertThat(challenges.size).isEqualTo(1)
    assertThat(challenges[0].scheme).isEqualTo("Digest")
    assertThat(challenges[0].realm).isNull()
    assertThat(challenges[0].authParams).isEqualTo(emptyMap<Any, Any>())
  }

  @Test fun basicChallenge() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate: Basic realm=\"protected area\"")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate"))
      .isEqualTo(listOf(Challenge("Basic", mapOf("realm" to "protected area"))))
  }

  @Test fun basicChallengeWithCharset() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate: Basic realm=\"protected area\", charset=\"UTF-8\"")
        .build()
    val expectedAuthParams = mutableMapOf<String?, String>()
    expectedAuthParams["realm"] = "protected area"
    expectedAuthParams["charset"] = "UTF-8"
    assertThat(headers.parseChallenges("WWW-Authenticate"))
      .isEqualTo(listOf(Challenge("Basic", expectedAuthParams)))
  }

  @Test fun basicChallengeWithUnexpectedCharset() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate: Basic realm=\"protected area\", charset=\"US-ASCII\"")
        .build()
    val expectedAuthParams = mutableMapOf<String?, String>()
    expectedAuthParams["realm"] = "protected area"
    expectedAuthParams["charset"] = "US-ASCII"
    assertThat(headers.parseChallenges("WWW-Authenticate"))
      .isEqualTo(listOf(Challenge("Basic", expectedAuthParams)))
  }

  @Test fun separatorsBeforeFirstChallenge() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", " ,  , Basic realm=myrealm")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate"))
      .isEqualTo(listOf(Challenge("Basic", mapOf("realm" to "myrealm"))))
  }

  @Test fun spacesAroundKeyValueSeparator() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Basic realm = \"myrealm\"")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate"))
      .isEqualTo(listOf(Challenge("Basic", mapOf("realm" to "myrealm"))))
  }

  @Test fun multipleChallengesInOneHeader() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Basic realm = \"myrealm\",Digest")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Basic", mapOf("realm" to "myrealm")),
      Challenge("Digest", mapOf()),
    )
  }

  @Test fun multipleChallengesWithSameSchemeButDifferentRealmInOneHeader() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Basic realm = \"myrealm\",Basic realm=myotherrealm")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Basic", mapOf("realm" to "myrealm")),
      Challenge("Basic", mapOf("realm" to "myotherrealm")),
    )
  }

  @Test fun separatorsBeforeFirstAuthParam() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Digest, Basic ,,realm=\"myrealm\"")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", mapOf("realm" to "myrealm")),
    )
  }

  @Test fun onlyCommaBetweenChallenges() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Digest,Basic realm=\"myrealm\"")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", mapOf("realm" to "myrealm")),
    )
  }

  @Test fun multipleSeparatorsBetweenChallenges() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,realm=\"myrealm\"")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", mapOf("realm" to "myrealm")),
    )
  }

  @Test fun unknownAuthParams() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,foo=bar,realm=\"myrealm\"")
        .build()
    val expectedAuthParams = mutableMapOf<String?, String>()
    expectedAuthParams["realm"] = "myrealm"
    expectedAuthParams["foo"] = "bar"
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", expectedAuthParams),
    )
  }

  @Test fun escapedCharactersInQuotedString() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\\\\\\\"r\\ealm\"")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", mapOf("realm" to "my\\\"realm")),
    )
  }

  @Test fun commaInQuotedStringAndBeforeFirstChallenge() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", ",Digest,,,, Basic ,,,realm=\"my, realm,\"")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", mapOf("realm" to "my, realm,")),
    )
  }

  @Test fun unescapedDoubleQuoteInQuotedStringWithEvenNumberOfBackslashesInFront() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\\\\\\\\\"r\\ealm\"")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
    )
  }

  @Test fun unescapedDoubleQuoteInQuotedString() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=\"my\"realm\"")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
    )
  }

  @Disabled("TODO(jwilson): reject parameters that use invalid characters")
  @Test
  fun doubleQuoteInToken() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Digest,,,, Basic ,,,realm=my\"realm")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
    )
  }

  @Test fun token68InsteadOfAuthParams() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Other abc==")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate"))
      .isEqualTo(
        listOf(Challenge("Other", mapOf(null to "abc=="))),
      )
  }

  @Test fun token68AndAuthParams() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Other abc==, realm=myrealm")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Other", mapOf(null to "abc==")),
    )
  }

  @Test fun repeatedAuthParamKey() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Other realm=myotherrealm, realm=myrealm")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).isEqualTo(listOf<Any>())
  }

  @Test fun multipleAuthenticateHeaders() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Digest")
        .add("WWW-Authenticate", "Basic realm=myrealm")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Digest", mapOf()),
      Challenge("Basic", mapOf("realm" to "myrealm")),
    )
  }

  @Test fun multipleAuthenticateHeadersInDifferentOrder() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Basic realm=myrealm")
        .add("WWW-Authenticate", "Digest")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Basic", mapOf("realm" to "myrealm")),
      Challenge("Digest", mapOf()),
    )
  }

  @Test fun multipleBasicAuthenticateHeaders() {
    val headers =
      Headers
        .Builder()
        .add("WWW-Authenticate", "Basic realm=myrealm")
        .add("WWW-Authenticate", "Basic realm=myotherrealm")
        .build()
    assertThat(headers.parseChallenges("WWW-Authenticate")).containsExactly(
      Challenge("Basic", mapOf("realm" to "myrealm")),
      Challenge("Basic", mapOf("realm" to "myotherrealm")),
    )
  }
}
