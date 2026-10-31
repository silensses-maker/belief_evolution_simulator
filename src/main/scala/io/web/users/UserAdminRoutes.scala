package io.web.users

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCode, StatusCodes}
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.{Directive1, Route}
import io.db.DatabaseManager.UserData
import io.web.auth.ErrorResponses.errorJson
import io.web.auth.{AuthenticatedUser, FieldIssue, FirebaseAuthDirective, Roles}
import io.web.json.ApiJson
import spray.json.*
import utils.logging.Logger

/** Why a user-administration write or read did not produce a row. */
sealed trait UserAdminError
object UserAdminError {
    /** No account with that Firebase uid. */
    case object NotFound extends UserAdminError
    /** The database failed; `message` is logged, never sent. */
    final case class Failure(message: String) extends UserAdminError
}

/** One page of accounts plus the total that matches the same filter. */
final case class UserPage(users: Seq[UserData], total: Int)

/**
 * What the user-administration endpoints need from the database (DatabaseManager implements it;
 * the route tests use a stub).
 */
trait UserAdminStore {
    /** `query` is a trimmed, case-insensitive substring of the email or the name; empty = every account. */
    def listUsers(limit: Int, offset: Int, query: String): Either[UserAdminError, UserPage]

    /** Replaces the account's roles with `{Guest, role}` (`{Guest}` for Guest) and returns the account. */
    def setUserRole(firebaseUid: String, role: String): Either[UserAdminError, UserData]

    /** Sets `users.deactivated` (idempotent) and returns the account. */
    def setUserDeactivated(firebaseUid: String, deactivated: Boolean): Either[UserAdminError, UserData]
}

/**
 * User administration (migration 007), mounted by Server INSIDE `pathPrefix("api" / "users")`,
 * after the self-service routes (`sync`, `info/{uid}`, `me/deactivate`, the additive
 * `role/{uid}/{role}`), so `me/deactivate` keeps answering to the caller's own account:
 *
 *  - `GET  /api/users?limit&offset&q` (SuperAdministrator): one page of every account, newest
 *    first, `{users: [AdminUser], total, limit, offset}`; `q` filters by email or name.
 *  - `GET  /api/users/roles` (authenticated): the role catalog, highest priority first, each with
 *    its quotas — what a client offers in a role selector.
 *  - `PUT  /api/users/{uid}/role` (SuperAdministrator), body `{"role": "Researcher"}`: replaces
 *    the account's roles with `{Guest, role}` and answers the account. 403 on the caller's own
 *    uid — nobody demotes themselves by accident; another SuperAdministrator can.
 *  - `POST /api/users/{uid}/deactivate` and `/reactivate` (SuperAdministrator): `users.deactivated`;
 *    a deactivated account gets 401 from `authenticate` on its next call. 403 on the caller's own
 *    uid for deactivate (`POST /api/users/me/deactivate` is the self-service way).
 *
 * Errors ({error, message[, fields]}): 400 `invalid_body` (paging, role), 401 `unauthorized`,
 * 403 `forbidden`, 404 `not_found`, 500 `internal_error`.
 */
object UserAdminRoutes {

    final val DefaultLimit = 50
    final val MaxLimit     = 200
    final val MaxQueryChars = 256

    final val OwnRoleMessage       = "Cannot change your own role"
    final val OwnDeactivateMessage = "Cannot deactivate your own account here; use POST /api/users/me/deactivate"

    def apply(authenticate: Directive1[AuthenticatedUser], store: UserAdminStore): Route = {
        val requireSuperAdmin = FirebaseAuthDirective.requireSuperAdminWith(authenticate)

        pathEndOrSingleSlash {
            get {
                requireSuperAdmin { _ =>
                    parameters("limit".as[Int].withDefault(DefaultLimit), "offset".as[Int].withDefault(0),
                               "q".withDefault("")) { (limit, offset, q) =>
                        val query  = q.trim
                        val issues = pagingIssues(limit, offset, query)
                        if (issues.nonEmpty)
                            complete(error(StatusCodes.BadRequest, "invalid_body", "Invalid paging parameters", issues))
                        else store.listUsers(limit, offset, query) match {
                            case Right(page) =>
                                complete(jsonOk(ApiJson.userList(page.users, page.total, limit, offset)))
                            case Left(failure) =>
                                complete(storeFailure("list users", failure))
                        }
                    }
                }
            }
        } ~
        path("roles") {
            get {
                authenticate { _ =>
                    complete(jsonOk(ApiJson.roleCatalog))
                }
            }
        } ~
        path(Segment / "role") { targetUid =>
            put {
                requireSuperAdmin { admin =>
                    entity(as[JsValue]) { json =>
                        parseRole(json) match {
                            case Left(issue) =>
                                complete(error(StatusCodes.BadRequest, "invalid_body", "Invalid role", List(issue)))
                            case Right(_) if admin.uid == targetUid =>
                                complete(error(StatusCodes.Forbidden, "forbidden", OwnRoleMessage))
                            case Right(role) =>
                                store.setUserRole(targetUid, role) match {
                                    case Right(user) =>
                                        Logger.log(s"Role of $targetUid set to $role by ${admin.uid}")
                                        complete(jsonOk(ApiJson.adminUser(user)))
                                    case Left(failure) =>
                                        complete(storeFailure(s"set role of $targetUid", failure))
                                }
                        }
                    }
                }
            }
        } ~
        path(Segment / "deactivate") { targetUid =>
            post {
                requireSuperAdmin { admin =>
                    if (admin.uid == targetUid)
                        complete(error(StatusCodes.Forbidden, "forbidden", OwnDeactivateMessage))
                    else setDeactivated(store, targetUid, deactivated = true, admin)
                }
            }
        } ~
        path(Segment / "reactivate") { targetUid =>
            post {
                requireSuperAdmin { admin =>
                    setDeactivated(store, targetUid, deactivated = false, admin)
                }
            }
        }
    }

    private def setDeactivated(store: UserAdminStore, targetUid: String, deactivated: Boolean,
                               admin: AuthenticatedUser): Route =
        store.setUserDeactivated(targetUid, deactivated) match {
            case Right(user) =>
                Logger.log(s"User $targetUid ${if (deactivated) "deactivated" else "reactivated"} by ${admin.uid}")
                complete(jsonOk(ApiJson.adminUser(user)))
            case Left(failure) =>
                complete(storeFailure(s"${if (deactivated) "deactivate" else "reactivate"} $targetUid", failure))
        }

    /** `limit` in `[1, MaxLimit]`, `offset >= 0`, `q` at most [[MaxQueryChars]] characters. */
    def pagingIssues(limit: Int, offset: Int, query: String): List[FieldIssue] = {
        val limitIssue  = Option.when(limit < 1 || limit > MaxLimit)(FieldIssue("limit", s"must be between 1 and $MaxLimit"))
        val offsetIssue = Option.when(offset < 0)(FieldIssue("offset", "must be 0 or more"))
        val queryIssue  = Option.when(query.length > MaxQueryChars)(FieldIssue("q", s"must be at most $MaxQueryChars characters"))
        List(limitIssue, offsetIssue, queryIssue).flatten
    }

    /** `{"role": <one of Roles.all>}`; anything else is one `invalid_body` issue on `role`. */
    def parseRole(json: JsValue): Either[FieldIssue, String] = {
        val expected = s"must be one of ${Roles.all.mkString(", ")}"
        json match {
            case JsObject(fields) => fields.get("role") match {
                case Some(JsString(role)) if Roles.isValid(role) => Right(role)
                case Some(JsString(_))                           => Left(FieldIssue("role", expected))
                case Some(_)                                     => Left(FieldIssue("role", "must be a string"))
                case None                                        => Left(FieldIssue("role", "is required"))
            }
            case _ => Left(FieldIssue("role", "the body must be a JSON object"))
        }
    }

    private def storeFailure(action: String, failure: UserAdminError): HttpResponse = failure match {
        case UserAdminError.NotFound =>
            error(StatusCodes.NotFound, "not_found", "User not found")
        case UserAdminError.Failure(message) =>
            Logger.logError(s"User administration: failed to $action: $message")
            error(StatusCodes.InternalServerError, "internal_error", "Internal server error")
    }

    private def jsonOk(value: JsValue): HttpResponse =
        HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, value.compactPrint))

    private def error(status: StatusCode, code: String, message: String, fields: Seq[FieldIssue] = Nil): HttpResponse =
        HttpResponse(status, entity = errorJson(code, message, fields))
}
