package io.web.auth

import com.google.firebase.auth.{AuthErrorCode, FirebaseAuth, FirebaseAuthException, FirebaseToken}
import utils.logging.Logger

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.{ArrayBlockingQueue, Callable, ExecutionException, FutureTask, ThreadFactory,
    ThreadPoolExecutor, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicInteger
import scala.util.{Failure, Success, Try}

/** Identity claims of a verified Firebase ID token, plus its `exp` (epoch millis, 0 if absent). */
final case class VerifiedToken(uid: String, email: String, name: String, photo: Option[String],
                               expiresAtMillis: Long)

/** Why an ID token was refused (#123). */
enum TokenRejection {
    /** Valid signature, but the session was revoked, or the Firebase account is disabled or deleted. */
    case Revoked
    /** Expired, malformed, wrong project, or its signature could not be verified at all. */
    case Invalid
}

final case class TokenFailure(rejection: TokenRejection, detail: String)

/** How a failed revocation check (step 2 of [[TokenVerifier]]) is treated. */
enum RevocationCheckFailure {
    /** Firebase answered: revoked session, disabled or deleted account → refuse the token. */
    case Revoked
    /** Firebase answered that the token itself is not valid (it expired between the two steps). */
    case Invalid
    /** The check could not run (network, timeout, credentials, outage, anything else) → fail open. */
    case Unavailable
}

/**
 * Firebase ID token verification shared by REST ([[FirebaseAuthDirective]]) and the WebSocket
 * auth (`WsConnectionActor`), with a FAIL-OPEN revocation check (#123):
 *
 *  1. **Signature, locally** — `verifyIdToken(token)`, the check every request had before #123
 *     (signature against Google's cached public keys, `exp`, audience, issuer). A failure is
 *     [[TokenRejection.Invalid]] (401 `unauthorized` "Invalid or expired token"; WS `auth_failed`
 *     without `reason`) and Firebase's user API is never called for that token.
 *  2. **Revocation, remotely** (only on a cache miss) — `verifyIdToken(token, checkRevoked = true)`:
 *     firebase-admin 9.5.0 (`RevocationCheckDecorator`) loads the user record (`getUserById`) and
 *     refuses a disabled account (`USER_DISABLED`), a deleted one (`USER_NOT_FOUND`) and a token
 *     whose `iat` is before the user's `tokensValidAfterTimestamp` (`REVOKED_ID_TOKEN`). Those are
 *     [[TokenRejection.Revoked]] (401 `token_revoked`; WS `auth_failed` + `reason: token_revoked`).
 *     If the check cannot run — network error, timeout ([[RevocationCheckDeadlineMillis]]),
 *     missing permission or credentials, Firebase outage, anything unexpected — the identity of
 *     step 1 is ACCEPTED and a rate-limited WARNING is logged (at most one line per
 *     [[UnavailableWarningIntervalMillis]], never the token): an outage of the user API must not
 *     log every user out.
 *
 * Successful outcomes are cached per token (SHA-256 key, never past the token's `exp`): a passed
 * revocation check for [[RevocationCheckTtlMillis]] (5 min — the longest a revocation goes
 * unnoticed), an accepted-without-check token for only [[UncheckedTtlMillis]] (60 s, so an outage
 * costs at most one Firebase round trip per token per minute and recovery is quick). Refusals
 * (Invalid, Revoked) are never cached.
 */
object TokenVerifier {

    /**
     * How long a PASSED revocation check of one token is reused: the upper bound of the delay
     * between a revocation and its effect. A constant on purpose (no env var): it is a security
     * trade-off, not a deployment knob; change it here if needed.
     */
    final val RevocationCheckTtlMillis: Long = 5L * 60 * 1000

    /** How long a token accepted WITHOUT the revocation check (the check could not run) is reused. */
    final val UncheckedTtlMillis: Long = 60L * 1000

    /**
     * Upper bound of one revocation check. firebase-admin's own HTTP timeouts are the transport
     * defaults (20 s connect + 20 s read) plus retries, so without this a hanging user API would
     * stall every authentication; past it the check counts as unavailable (fail-open).
     */
    final val RevocationCheckDeadlineMillis: Long = 3_000

    /** At most one "revocation check unavailable" WARNING per this interval. */
    final val UnavailableWarningIntervalMillis: Long = 60L * 1000

    /** Upper bound of cached outcomes (~200 bytes each); past it the oldest entry is evicted. */
    final val CacheMaxEntries: Int = 10_000

    private val cache = new VerifiedTokenCache(CacheMaxEntries)

    private val unavailableWarning = new RateLimitedWarning(UnavailableWarningIntervalMillis, Logger.logWarning)

    // Revocation checks run here under RevocationCheckDeadlineMillis. Bounded (threads and queue):
    // when saturated, a check is rejected, which counts as unavailable. Daemon threads.
    private val checkExecutor: ThreadPoolExecutor = {
        val counter = new AtomicInteger(0)
        val factory: ThreadFactory = (runnable: Runnable) => {
            val thread = new Thread(runnable, s"firebase-revocation-check-${counter.incrementAndGet()}")
            thread.setDaemon(true)
            thread
        }
        val executor = new ThreadPoolExecutor(8, 8, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue[Runnable](256), factory)
        executor.allowCoreThreadTimeOut(true)
        executor
    }

    /** Verifies `idToken` (steps 1 and 2 above). Blocking: up to the deadline on a cache miss. */
    def verify(idToken: String): Either[TokenFailure, VerifiedToken] =
        verifyWith(cache, unavailableWarning, idToken)(
            verifySignature = token => fromFirebaseToken(FirebaseAuth.getInstance().verifyIdToken(token)),
            checkRevocation = token => withDeadline(RevocationCheckDeadlineMillis) {
                FirebaseAuth.getInstance().verifyIdToken(token, true)
                ()
            }
        )

    /** Drops the expired cached outcomes (memory hygiene; expired entries are never served anyway). */
    def purgeExpired(): Int = cache.purgeExpired()

    /**
     * [[verify]] with an explicit cache, warning and checks: `verifySignature` (step 1) returns the
     * identity or throws; `checkRevocation` (step 2) returns normally when the token is not revoked
     * and throws otherwise.
     */
    def verifyWith(cache: VerifiedTokenCache, unavailable: RateLimitedWarning, idToken: String)
                  (verifySignature: String => VerifiedToken,
                   checkRevocation: String => Unit): Either[TokenFailure, VerifiedToken] =
        cache.get(idToken) match {
            case Some(identity) => Right(identity)
            case None =>
                Try(verifySignature(idToken)) match {
                    case Failure(e) => Left(TokenFailure(TokenRejection.Invalid, describe(e, idToken)))
                    case Success(identity) =>
                        Try(checkRevocation(idToken)) match {
                            case Success(_) =>
                                cache.put(idToken, identity, RevocationCheckTtlMillis)
                                Right(identity)
                            case Failure(e) => classifyRevocationFailure(e) match {
                                case RevocationCheckFailure.Revoked =>
                                    Left(TokenFailure(TokenRejection.Revoked, describe(e, idToken)))
                                case RevocationCheckFailure.Invalid =>
                                    Left(TokenFailure(TokenRejection.Invalid, describe(e, idToken)))
                                case RevocationCheckFailure.Unavailable =>
                                    unavailable.warn(
                                        s"Firebase revocation check unavailable (${describe(e, idToken)}): accepting " +
                                        s"signature-verified ID tokens without it (fail-open), each for at most " +
                                        s"${UncheckedTtlMillis / 1000} s")
                                    cache.put(idToken, identity, UncheckedTtlMillis)
                                    Right(identity)
                            }
                        }
                }
        }

    /**
     * A failure of step 2. Only an answer from Firebase about the ACCOUNT refuses the token
     * (revoked session, disabled or deleted account); `EXPIRED_ID_TOKEN` / `INVALID_ID_TOKEN` mean
     * the token expired between the two steps. Everything else — no auth error code (network /
     * HTTP errors), `CERTIFICATE_FETCH_FAILED`, `CONFIGURATION_NOT_FOUND`, the deadline, a full
     * check pool, `FirebaseApp` not initialized, any other exception — is [[RevocationCheckFailure.Unavailable]].
     */
    def classifyRevocationFailure(error: Throwable): RevocationCheckFailure = error match {
        case e: FirebaseAuthException => e.getAuthErrorCode match {
            case AuthErrorCode.REVOKED_ID_TOKEN | AuthErrorCode.USER_DISABLED | AuthErrorCode.USER_NOT_FOUND =>
                RevocationCheckFailure.Revoked
            case AuthErrorCode.EXPIRED_ID_TOKEN | AuthErrorCode.INVALID_ID_TOKEN =>
                RevocationCheckFailure.Invalid
            case _ =>
                RevocationCheckFailure.Unavailable
        }
        case _ => RevocationCheckFailure.Unavailable
    }

    /**
     * Runs `body` on the bounded check pool and waits at most `millis`. Throws the body's own
     * exception, a TimeoutException past the deadline (the task is then interrupted), or a
     * RejectedExecutionException when the pool is saturated.
     */
    def withDeadline(millis: Long)(body: => Unit): Unit = {
        val task = new FutureTask[Unit](new Callable[Unit] { def call(): Unit = body })
        checkExecutor.execute(task)
        try task.get(millis, TimeUnit.MILLISECONDS)
        catch {
            case e: ExecutionException if e.getCause != null => throw e.getCause
            case e: TimeoutException =>
                task.cancel(true)
                throw new TimeoutException(s"revocation check took more than $millis ms")
        }
    }

    /** For the server log only (never sent to the client): one line, bounded, without the token. */
    private def describe(error: Throwable, idToken: String): String = {
        val text = error match {
            case e: FirebaseAuthException => s"${Option(e.getAuthErrorCode).getOrElse("no auth error code")}: ${e.getMessage}"
            case e                        => s"${e.getClass.getSimpleName}: ${e.getMessage}"
        }
        val scrubbed = if (idToken.nonEmpty) text.replace(idToken, "<token>") else text
        scrubbed.replaceAll("\\s+", " ").take(300)
    }

    private def fromFirebaseToken(token: FirebaseToken): VerifiedToken =
        VerifiedToken(
            uid             = token.getUid,
            email           = Option(token.getEmail).getOrElse(""),
            name            = Option(token.getName).getOrElse(""),
            photo           = Option(token.getPicture),
            expiresAtMillis = token.getClaims.get("exp") match {
                case seconds: java.lang.Number => seconds.longValue * 1000
                case _                         => 0L // not cached (see VerifiedTokenCache.put)
            }
        )
}

/**
 * Logs `message` at most once per `intervalMillis`; the next line that gets through says how many
 * were suppressed meanwhile. Thread-safe.
 */
final class RateLimitedWarning(intervalMillis: Long, sink: String => Unit,
                               nowMillis: () => Long = () => System.currentTimeMillis()) {
    private var lastLoggedAt: Option[Long] = None
    private var suppressed: Long = 0L

    /** True when the line was logged, false when it was suppressed. */
    def warn(message: => String): Boolean = synchronized {
        val now = nowMillis()
        if (lastLoggedAt.forall(now - _ >= intervalMillis)) {
            val tail = if (suppressed > 0) s" [$suppressed similar warning(s) suppressed in the last ${intervalMillis / 1000} s]" else ""
            sink(message + tail)
            lastLoggedAt = Some(now)
            suppressed = 0L
            true
        } else {
            suppressed += 1
            false
        }
    }
}

/**
 * Accepted token outcomes, keyed by the SHA-256 of the token (the bearer token itself is not
 * kept). An entry is served until `min(storedAt + ttlMillis, token exp)`, so a cached token is
 * never accepted past its expiry; a token without a future `exp` is not cached. At most
 * `maxEntries` entries (the oldest inserted is evicted first). Thread-safe: one lock around a
 * LinkedHashMap, held only for the map operation (hashing happens outside it).
 */
final class VerifiedTokenCache(maxEntries: Int, nowMillis: () => Long = () => System.currentTimeMillis()) {
    require(maxEntries > 0, "maxEntries must be positive")

    private final case class Entry(token: VerifiedToken, validUntilMillis: Long)

    private val entries = new java.util.LinkedHashMap[String, Entry](64, 0.75f, false) {
        override protected def removeEldestEntry(eldest: java.util.Map.Entry[String, Entry]): Boolean =
            this.size() > maxEntries
    }

    def get(idToken: String): Option[VerifiedToken] = {
        val key = VerifiedTokenCache.keyOf(idToken)
        val now = nowMillis()
        entries.synchronized {
            val entry = entries.get(key)
            if (entry == null) None
            else if (now < entry.validUntilMillis) Some(entry.token)
            else {
                entries.remove(key)
                None
            }
        }
    }

    def put(idToken: String, token: VerifiedToken, ttlMillis: Long): Unit = {
        val now        = nowMillis()
        val validUntil = math.min(now + math.max(0L, ttlMillis), token.expiresAtMillis)
        if (validUntil > now) {
            val key = VerifiedTokenCache.keyOf(idToken)
            entries.synchronized { entries.put(key, Entry(token, validUntil)) }
        }
    }

    def size: Int = entries.synchronized(entries.size)

    def purgeExpired(): Int = {
        val now = nowMillis()
        entries.synchronized {
            val before = entries.size
            entries.values.removeIf(_.validUntilMillis <= now)
            before - entries.size
        }
    }
}

object VerifiedTokenCache {
    /** Hex SHA-256 of the token: the cache key, so the cache never holds a usable bearer token. */
    def keyOf(idToken: String): String = {
        val digest = MessageDigest.getInstance("SHA-256").digest(idToken.getBytes(StandardCharsets.UTF_8))
        val hex    = new java.lang.StringBuilder(digest.length * 2)
        digest.foreach(b => hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16)))
        hex.toString
    }
}
