package io.web.auth

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.*
import io.db.DatabaseManager
import io.web.auth.ErrorResponses.*
import utils.logging.Logger

object FirebaseAuthDirective {

    /**
     * 401 body of a token with a valid signature whose session was revoked, or whose Firebase
     * account is disabled or deleted (#123).
     */
    final val TokenRevokedCode    = "token_revoked"
    final val TokenRevokedMessage = "The session was revoked or the account is disabled; sign in again"

    // Verifies the Bearer token (TokenVerifier, #123: signature locally first, then a FAIL-OPEN
    // revocation check — if Firebase's user API cannot be reached the signature-verified identity is
    // accepted and a rate-limited warning is logged), loads roles from DB, and provides
    // AuthenticatedUser. 401 if the token is missing/invalid/expired (`unauthorized`), revoked or of
    // a disabled / deleted Firebase account (`token_revoked`), or if the user is deactivated in the
    // database (`unauthorized`, "Account is deactivated").
    val authenticate: Directive1[AuthenticatedUser] =
        optionalHeaderValueByName("Authorization").flatMap {
            case Some(header) if header.startsWith("Bearer ") =>
                val idToken = header.stripPrefix("Bearer ").trim
                TokenVerifier.verify(idToken) match {
                    case Right(token) =>
                        val uid   = token.uid
                        val email = token.email
                        val name  = token.name
                        val photo = token.photo

                        DatabaseManager.createOrUpdateUser(uid, email, name, photo) match {
                            case Some(user) if user.deactivated =>
                                complete(StatusCodes.Unauthorized ->
                                    errorJson("unauthorized", "Account is deactivated"))
                            case Some(user) =>
                                val bootstrapEmails = sys.env
                                    .getOrElse("BOOTSTRAP_ADMIN_EMAILS", "")
                                    .split(",")
                                    .map(_.trim)
                                    .filter(_.nonEmpty)

                                val isAdmin = user.roles.contains("Administrator")
                                if (bootstrapEmails.contains(email) && !isAdmin) {
                                    DatabaseManager.addUserRole(uid, "Administrator")
                                    Logger.log(s"Bootstrap admin promoted: $email")
                                }

                                val finalRoles =
                                    if (bootstrapEmails.contains(email) && !isAdmin)
                                        user.roles :+ "Administrator"
                                    else
                                        user.roles

                                provide(AuthenticatedUser(
                                    uid      = uid,
                                    email    = email,
                                    name     = name,
                                    photo    = photo,
                                    roles    = finalRoles,
                                    dbUserId = user.id
                                ))
                            case None =>
                                complete(StatusCodes.InternalServerError ->
                                    errorJson("internal_error", "Failed to resolve user"))
                        }

                    case Left(TokenFailure(TokenRejection.Revoked, detail)) =>
                        Logger.log(s"Token rejected (revoked session or disabled account): $detail")
                        complete(StatusCodes.Unauthorized -> errorJson(TokenRevokedCode, TokenRevokedMessage))

                    case Left(TokenFailure(TokenRejection.Invalid, detail)) =>
                        Logger.log(s"Token verification failed: $detail")
                        complete(StatusCodes.Unauthorized ->
                            errorJson("unauthorized", "Invalid or expired token"))
                }

            case _ =>
                complete(StatusCodes.Unauthorized ->
                    errorJson("unauthorized", "Missing Bearer token"))
        }

    // Shortcut: authenticate + require Administrator role
    val requireAdmin: Directive1[AuthenticatedUser] =
        authenticate.flatMap { user =>
            if (user.isAdmin) provide(user)
            else complete(StatusCodes.Forbidden ->
                errorJson("forbidden", "Administrator role required"))
        }
}
