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
    // accepted and a rate-limited warning is logged), loads roles from DB (promoting the
    // BOOTSTRAP_ADMIN_EMAILS accounts to SuperAdministrator), and provides AuthenticatedUser. 401 if the token is missing/invalid/expired (`unauthorized`), revoked or of
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
                                // Bootstrap (migration 007): the accounts of BOOTSTRAP_ADMIN_EMAILS
                                // become SuperAdministrator on their first authenticated request
                                // (they used to become Administrator; the roles they already hold
                                // are kept). Never revoked here: removing an email from the
                                // variable leaves the role in the database.
                                val promote = bootstrapEmails.contains(email) &&
                                    !user.roles.contains(Roles.SuperAdministrator)
                                if (promote) {
                                    if (DatabaseManager.addUserRole(uid, Roles.SuperAdministrator))
                                        Logger.log(s"Bootstrap super administrator promoted: $email")
                                    else
                                        Logger.logError(s"Bootstrap super administrator promotion failed: $email")
                                }

                                val finalRoles =
                                    if (promote) user.roles :+ Roles.SuperAdministrator
                                    else user.roles

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

    /** `BOOTSTRAP_ADMIN_EMAILS`, comma-separated and trimmed; read per request so a restart is not needed. */
    private def bootstrapEmails: Seq[String] =
        sys.env.getOrElse("BOOTSTRAP_ADMIN_EMAILS", "").split(",").map(_.trim).filter(_.nonEmpty).toSeq

    // Shortcut: authenticate + require Administrator (or SuperAdministrator) role
    val requireAdmin: Directive1[AuthenticatedUser] =
        authenticate.flatMap { user =>
            if (user.isAdmin) provide(user)
            else complete(StatusCodes.Forbidden ->
                errorJson("forbidden", "Administrator role required"))
        }

    final val SuperAdminRequiredMessage = "SuperAdministrator role required"

    /** `authenticate` + SuperAdministrator role, else 403 [[SuperAdminRequiredMessage]] (user administration). */
    val requireSuperAdmin: Directive1[AuthenticatedUser] = requireSuperAdminWith(authenticate)

    /** [[requireSuperAdmin]] on top of any authentication directive (the route tests use a stub). */
    def requireSuperAdminWith(auth: Directive1[AuthenticatedUser]): Directive1[AuthenticatedUser] =
        auth.flatMap { user =>
            if (user.isSuperAdmin) provide(user)
            else complete(StatusCodes.Forbidden -> errorJson("forbidden", SuperAdminRequiredMessage))
        }
}
