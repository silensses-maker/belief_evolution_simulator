package io.web.auth

import com.google.firebase.ErrorCode
import com.google.firebase.auth.{AuthErrorCode, FirebaseAuthException}

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, Executors, RejectedExecutionException, TimeUnit, TimeoutException}
import scala.collection.mutable.ArrayBuffer

/**
 * Token verification of #123 without Firebase: step 1 (signature, local) and step 2 (revocation
 * check, FAIL-OPEN) are stubs; this checks the classification, which outcomes are cached and for
 * how long, the rate-limited warning and the deadline. `TokenVerifier.verify` is `verifyWith` with
 * `verifyIdToken(token)` and `verifyIdToken(token, checkRevoked = true)` under the deadline.
 */
class TokenVerifierSuite extends munit.FunSuite {

    private val CheckedTtl   = TokenVerifier.RevocationCheckTtlMillis
    private val UncheckedTtl = TokenVerifier.UncheckedTtlMillis
    private val HourMs       = 3_600_000L

    private final class Clock(var now: Long)

    /** A verifier wired to stubs that count their calls; the warning lines are collected. */
    private final class Harness(start: Long = 1_000_000L, maxEntries: Int = 100) {
        val clock      = new Clock(start)
        val cache      = new VerifiedTokenCache(maxEntries, () => clock.now)
        val warnings   = ArrayBuffer.empty[String]
        val warning    = new RateLimitedWarning(TokenVerifier.UnavailableWarningIntervalMillis, warnings += _, () => clock.now)
        var signatureCalls  = 0
        var revocationCalls = 0
        /** Step 1 throws this when set (invalid signature / expired token). */
        var signatureError: Option[Throwable] = None
        /** Step 2 throws this when set (revoked, disabled, deleted, or the check cannot run). */
        var revocationError: Option[Throwable] = None
        /** Same, built from the token (takes precedence). */
        var revocationErrorFor: Option[String => Throwable] = None
        /** `exp` of the tokens step 1 returns, relative to the clock. */
        var expiresInMillis: Long = HourMs

        def verify(token: String): Either[TokenFailure, VerifiedToken] =
            TokenVerifier.verifyWith(cache, warning, token)(
                verifySignature = t => {
                    signatureCalls += 1
                    signatureError.foreach(e => throw e)
                    VerifiedToken(s"uid-of-$t", s"$t@x.io", "Name", None, clock.now + expiresInMillis)
                },
                checkRevocation = t => {
                    revocationCalls += 1
                    revocationErrorFor.foreach(f => throw f(t))
                    revocationError.foreach(e => throw e)
                }
            )
    }

    private def authError(code: AuthErrorCode, message: String = "firebase says no"): FirebaseAuthException =
        new FirebaseAuthException(ErrorCode.INVALID_ARGUMENT, s"$message ($code)", null, null, code)

    private def networkError(message: String = "Connect timed out"): FirebaseAuthException =
        new FirebaseAuthException(ErrorCode.UNAVAILABLE, message, new IOException(message), null, null)

    private def rejection(result: Either[TokenFailure, VerifiedToken]): Option[TokenRejection] = result.left.toOption.map(_.rejection)

    test("TTLs: 5 minutes after a passed check, 60 s without the check; warnings at most once per minute") {
        assertEquals(CheckedTtl, 300_000L)
        assertEquals(UncheckedTtl, 60_000L)
        assertEquals(TokenVerifier.UnavailableWarningIntervalMillis, 60_000L)
    }

    test("classification of a failed revocation check") {
        List(AuthErrorCode.REVOKED_ID_TOKEN, AuthErrorCode.USER_DISABLED, AuthErrorCode.USER_NOT_FOUND).foreach { code =>
            assertEquals(TokenVerifier.classifyRevocationFailure(authError(code)), RevocationCheckFailure.Revoked, s"$code")
        }
        List(AuthErrorCode.EXPIRED_ID_TOKEN, AuthErrorCode.INVALID_ID_TOKEN).foreach { code =>
            assertEquals(TokenVerifier.classifyRevocationFailure(authError(code)), RevocationCheckFailure.Invalid, s"$code")
        }
        List[Throwable](
            networkError(),                                                   // HTTP / network: no auth error code
            authError(AuthErrorCode.CERTIFICATE_FETCH_FAILED),
            authError(AuthErrorCode.CONFIGURATION_NOT_FOUND),
            new FirebaseAuthException(ErrorCode.PERMISSION_DENIED, "caller lacks permission", null, null, null),
            new IllegalStateException("FirebaseApp with name [DEFAULT] doesn't exist"),
            new TimeoutException("revocation check took more than 3000 ms"),
            new RejectedExecutionException("pool saturated"),
            new IOException("Unknown host identitytoolkit.googleapis.com"),
            new RuntimeException("anything else")
        ).foreach { e =>
            assertEquals(TokenVerifier.classifyRevocationFailure(e), RevocationCheckFailure.Unavailable, e.toString)
        }
    }

    test("invalid signature → Invalid; the revocation check is never called and nothing is cached") {
        val h = new Harness
        h.signatureError = Some(authError(AuthErrorCode.INVALID_ID_TOKEN, "Failed to verify the signature"))
        (1 to 3).foreach(_ => assertEquals(rejection(h.verify("forged")), Some(TokenRejection.Invalid)))
        h.signatureError = Some(authError(AuthErrorCode.EXPIRED_ID_TOKEN))
        assertEquals(rejection(h.verify("expired")), Some(TokenRejection.Invalid))
        h.signatureError = Some(new IllegalStateException("FirebaseApp with name [DEFAULT] doesn't exist"))
        assertEquals(rejection(h.verify("no-firebase")), Some(TokenRejection.Invalid), "no signature check, no acceptance")
        assertEquals(h.signatureCalls, 5)
        assertEquals(h.revocationCalls, 0, "Firebase's user API is not contacted for such tokens")
        assertEquals(h.cache.size, 0)
        assert(h.warnings.isEmpty)
    }

    test("revoked session, disabled or deleted account → Revoked, never cached") {
        List(AuthErrorCode.REVOKED_ID_TOKEN, AuthErrorCode.USER_DISABLED, AuthErrorCode.USER_NOT_FOUND).foreach { code =>
            val h = new Harness
            h.revocationError = Some(authError(code))
            (1 to 2).foreach { _ =>
                h.verify("tok") match {
                    case Left(TokenFailure(TokenRejection.Revoked, detail)) => assert(detail.contains(code.toString), detail)
                    case other                                             => fail(s"$code: expected Revoked, got $other")
                }
            }
            assertEquals(h.revocationCalls, 2, s"$code: re-checked every time")
            assertEquals(h.cache.size, 0)
            assert(h.warnings.isEmpty)
        }
    }

    test("token expired between the two steps → Invalid, not accepted") {
        val h = new Harness
        h.revocationError = Some(authError(AuthErrorCode.EXPIRED_ID_TOKEN))
        assertEquals(rejection(h.verify("tok")), Some(TokenRejection.Invalid))
        assertEquals(h.cache.size, 0)
    }

    test("passed check: reused for 5 minutes, then both steps run again") {
        val h = new Harness
        assertEquals(h.verify("tok").map(_.uid), Right("uid-of-tok"))
        h.clock.now += CheckedTtl - 1
        assert(h.verify("tok").isRight)
        assertEquals((h.signatureCalls, h.revocationCalls), (1, 1), "served from the cache")
        h.clock.now += 1
        assert(h.verify("tok").isRight)
        assertEquals((h.signatureCalls, h.revocationCalls), (2, 2), "re-checked at the TTL")
    }

    test("a token revoked after a passed check is refused at most 5 minutes later") {
        val h = new Harness(start = 0L)
        assert(h.verify("tok").isRight)
        h.revocationError = Some(authError(AuthErrorCode.REVOKED_ID_TOKEN))  // revokeRefreshTokens
        h.clock.now = 60_000
        assert(h.verify("tok").isRight, "still within the cached check")
        h.clock.now = CheckedTtl
        assertEquals(rejection(h.verify("tok")), Some(TokenRejection.Revoked))
    }

    test("check unavailable → fail open: accepted, warned, cached only 60 s, then re-checked") {
        val h = new Harness
        h.revocationError = Some(networkError())
        assertEquals(h.verify("tok").map(_.uid), Right("uid-of-tok"), "identity of step 1 accepted")
        assertEquals(h.warnings.size, 1)
        assert(h.warnings.head.contains("fail-open"), h.warnings.head)
        h.clock.now += UncheckedTtl - 1
        assert(h.verify("tok").isRight)
        assertEquals(h.revocationCalls, 1, "at most one Firebase round trip per token per minute")
        h.clock.now += 1
        // Firebase is back and the session had been revoked meanwhile: refused at the next check.
        h.revocationError = Some(authError(AuthErrorCode.REVOKED_ID_TOKEN))
        assertEquals(rejection(h.verify("tok")), Some(TokenRejection.Revoked))
        assertEquals(h.revocationCalls, 2)
        // ... and once it passes again, the normal 5-minute reuse applies.
        h.revocationError = None
        assert(h.verify("tok").isRight)
        h.clock.now += CheckedTtl - 1
        assert(h.verify("tok").isRight)
        assertEquals(h.revocationCalls, 3)
    }

    test("an accepted-without-check token is never cached past its exp") {
        val h = new Harness
        h.expiresInMillis = 10_000                                            // expires before the 60 s
        h.revocationError = Some(new TimeoutException("revocation check took more than 3000 ms"))
        assert(h.verify("tok").isRight)
        h.clock.now += 9_999
        assert(h.verify("tok").isRight)
        assertEquals(h.revocationCalls, 1)
        h.clock.now += 1
        h.signatureError = Some(authError(AuthErrorCode.EXPIRED_ID_TOKEN))
        assertEquals(rejection(h.verify("tok")), Some(TokenRejection.Invalid), "at exp it is verified again, and refused")
    }

    test("the unavailable warning is rate-limited to one line per minute and never contains the token") {
        val h      = new Harness
        val secret = "eyJhbGciOiJSUzI1NiJ9.SECRET-PAYLOAD.SIGNATURE"
        // An error message that (unexpectedly) echoes the token must not leak it into the log.
        h.revocationErrorFor = Some(token => networkError(s"failed while sending $token upstream"))
        (1 to 50).foreach(i => assert(h.verify(s"$secret-$i").isRight))      // 50 distinct tokens: 50 cache misses
        assertEquals(h.revocationCalls, 50)
        assertEquals(h.warnings.size, 1, "one line for the whole minute")
        h.clock.now += TokenVerifier.UnavailableWarningIntervalMillis - 1
        assert(h.verify(s"$secret-x").isRight)
        assertEquals(h.warnings.size, 1)
        h.clock.now += 1
        assert(h.verify(s"$secret-y").isRight)
        assertEquals(h.warnings.size, 2)
        assert(h.warnings(1).contains("50 similar warning(s) suppressed"), h.warnings(1))
        h.warnings.foreach { line =>
            assert(!line.contains("SECRET-PAYLOAD"), s"token leaked: $line")
            assert(line.contains("<token>"), line)
        }
    }

    test("RateLimitedWarning on its own") {
        val clock = new Clock(0L)
        val lines = ArrayBuffer.empty[String]
        val warning = new RateLimitedWarning(1_000, lines += _, () => clock.now)
        assert(warning.warn("a"))
        assert(!warning.warn("b"))
        assert(!warning.warn("c"))
        clock.now = 1_000
        assert(warning.warn("d"))
        assertEquals(lines.toList, List("a", "d [2 similar warning(s) suppressed in the last 1 s]"))
    }

    test("the deadline: a hanging check fails fast and counts as unavailable; errors keep their code") {
        val t0 = System.nanoTime()
        val slow = scala.util.Try(TokenVerifier.withDeadline(200) { Thread.sleep(5_000) })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assert(slow.failed.get.isInstanceOf[TimeoutException], slow.toString)
        assert(ms < 2_000, s"took $ms ms")
        assertEquals(TokenVerifier.classifyRevocationFailure(slow.failed.get), RevocationCheckFailure.Unavailable)

        val revoked = scala.util.Try(TokenVerifier.withDeadline(2_000) { throw authError(AuthErrorCode.REVOKED_ID_TOKEN) })
        assertEquals(TokenVerifier.classifyRevocationFailure(revoked.failed.get), RevocationCheckFailure.Revoked)
        TokenVerifier.withDeadline(2_000) { () } // a fast check just returns
    }

    test("cache: tokens without a future exp are not cached; size bound; purge; SHA-256 keys") {
        val clock = new Clock(50_000L)
        val cache = new VerifiedTokenCache(3, () => clock.now)
        cache.put("expired", VerifiedToken("u1", "e", "n", None, clock.now), CheckedTtl)
        cache.put("no-exp", VerifiedToken("u2", "e", "n", None, 0L), CheckedTtl)
        assertEquals(cache.size, 0)

        (1 to 5).foreach(i => cache.put(s"tok$i", VerifiedToken(s"u$i", "e", "n", None, clock.now + HourMs), CheckedTtl))
        assertEquals(cache.size, 3)
        assertEquals(cache.get("tok1"), None)
        assertEquals((3 to 5).map(i => cache.get(s"tok$i").map(_.uid)), (3 to 5).map(i => Some(s"u$i")))

        val purgeable = new VerifiedTokenCache(10, () => clock.now)
        purgeable.put("short", VerifiedToken("u1", "e", "n", None, clock.now + HourMs), UncheckedTtl)
        purgeable.put("long", VerifiedToken("u2", "e", "n", None, clock.now + HourMs), CheckedTtl)
        clock.now += UncheckedTtl
        assertEquals(purgeable.purgeExpired(), 1)
        assertEquals(purgeable.get("long").map(_.uid), Some("u2"))

        val key = VerifiedTokenCache.keyOf("eyJhbGciOiJSUzI1NiJ9.payload.signature")
        assertEquals(key.length, 64)
        assert(key.forall(c => c.isDigit || ('a' to 'f').contains(c)), key)
        assertEquals(VerifiedTokenCache.keyOf("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    test("concurrent verifications, with the check sometimes unavailable, are safe and stay bounded") {
        val maxEntries = 500
        val cache      = new VerifiedTokenCache(maxEntries)
        val warnings   = new AtomicInteger(0)
        val warning    = new RateLimitedWarning(60_000, _ => warnings.incrementAndGet())
        val pool       = Executors.newFixedThreadPool(16)
        val start      = new CountDownLatch(1)
        val errors     = new AtomicInteger(0)
        val wrong      = new AtomicInteger(0)
        val checks     = new AtomicInteger(0)
        val exp        = System.currentTimeMillis() + HourMs
        try {
            (0 until 16).foreach { t =>
                pool.execute { () =>
                    start.await()
                    val rnd = new scala.util.Random(t)
                    try (0 until 5_000).foreach { _ =>
                        val i = rnd.nextInt(2_000)
                        TokenVerifier.verifyWith(cache, warning, s"token-$i")(
                            verifySignature = _ => VerifiedToken(s"user-$i", "e", "n", None, exp),
                            checkRevocation = _ => {
                                checks.incrementAndGet()
                                if (i % 3 == 0) throw networkError()
                                if (i % 7 == 0) throw authError(AuthErrorCode.REVOKED_ID_TOKEN)
                            }
                        ) match {
                            // i % 3 == 0: the check is unavailable (fail-open, accepted); otherwise
                            // i % 7 == 0: revoked; otherwise the check passes.
                            case Left(TokenFailure(TokenRejection.Revoked, _)) if i % 3 != 0 && i % 7 == 0 =>
                            case Right(v) if v.uid == s"user-$i" && (i % 3 == 0 || i % 7 != 0) =>
                            case _ => wrong.incrementAndGet()
                        }
                        if (rnd.nextInt(100) == 0) cache.purgeExpired()
                    } catch { case _: Throwable => errors.incrementAndGet() }
                }
            }
            start.countDown()
            pool.shutdown()
            assert(pool.awaitTermination(60, TimeUnit.SECONDS), "workers finished")
        } finally pool.shutdownNow()
        assertEquals(errors.get, 0)
        assertEquals(wrong.get, 0, "every token gets its own identity or its own refusal")
        assert(cache.size <= maxEntries, s"size ${cache.size}")
        assert(checks.get < 16 * 5_000, "some outcomes were served from the cache")
        assertEquals(warnings.get, 1, "one warning line within the minute")
    }
}
