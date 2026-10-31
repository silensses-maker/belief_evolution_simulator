package io.web.users

// The build imports jdk.incubator.vector.* everywhere (-Yimports): use Scala's Vector here.
import scala.collection.immutable.Vector

import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.*
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.testkit.{RouteTest, RouteTestTimeout, TestFrameworkInterface}
import io.db.DatabaseManager.UserData
import io.web.auth.ErrorResponses.errorJson
import io.web.auth.{AuthenticatedUser, FirebaseAuthDirective}
import io.web.testing.JsonTestSupport.*
import io.web.{Cors, ErrorHandling}
import spray.json.*

import scala.concurrent.duration.*

/**
 * Migration 007: the user-administration endpoints end to end on a route tree shaped like
 * Server's (CORS, dual mount, global JSON error handlers, the self-service `me/deactivate` route
 * mounted before them), with a stub authenticator (`Bearer super | admin | researcher`) and an
 * in-memory store.
 */
class UserAdminRoutesSuite extends munit.FunSuite with RouteTest with TestFrameworkInterface {

    override def failTest(msg: String): Nothing = throw new AssertionError(msg)
    override def testExceptionHandler: ExceptionHandler = ExceptionHandler { case e: AssertionError => throw e }
    override def afterAll(): Unit = { cleanUp(); super.afterAll() }
    implicit val routeTimeout: RouteTestTimeout = RouteTestTimeout(30.seconds)

    private val superAdmin = AuthenticatedUser("uid-super", "s@x.io", "Super", None, Seq("Guest", "SuperAdministrator"), 1)
    private val admin      = AuthenticatedUser("uid-admin", "a@x.io", "Admin", None, Seq("Guest", "Administrator"), 2)
    private val researcher = AuthenticatedUser("uid-res", "r@x.io", "Res", None, Seq("Guest", "Researcher"), 3)

    private val stubAuthenticate: Directive1[AuthenticatedUser] =
        optionalHeaderValueByName("Authorization").flatMap {
            case Some("Bearer super")      => provide(superAdmin)
            case Some("Bearer admin")      => provide(admin)
            case Some("Bearer researcher") => provide(researcher)
            case _ => complete(StatusCodes.Unauthorized -> errorJson("unauthorized", "Missing Bearer token"))
        }

    private def account(id: Int, uid: String, email: String, name: String, roles: Seq[String],
                        deactivated: Boolean = false): UserData =
        new UserData(id, uid, email, name, None, roles, deactivated, s"2026-10-3${id}T10:00:00Z", "2026-10-31T12:00:00Z")

    private val seed = Seq(
        account(1, "uid-super", "s@x.io", "Super", Seq("Guest", "SuperAdministrator")),
        account(2, "uid-admin", "a@x.io", "Admin", Seq("Guest", "Administrator")),
        account(3, "uid-res", "r@x.io", "Res Earcher", Seq("Guest", "Researcher")),
        account(4, "uid-guest", "g@x.io", "Guest Only", Seq("Guest"), deactivated = true)
    )

    /** In-memory accounts, newest (highest id) first like the SQL; records every call. */
    private final class StubStore(var failing: Boolean = false) extends UserAdminStore {
        var users: Seq[UserData] = seed.sortBy(-_.id)
        var calls: List[String]  = Nil

        private def matches(u: UserData, query: String): Boolean =
            query.isEmpty || u.email.toLowerCase.contains(query.toLowerCase) ||
                u.name.toLowerCase.contains(query.toLowerCase)

        def listUsers(limit: Int, offset: Int, query: String): Either[UserAdminError, UserPage] = {
            calls ::= s"list($limit,$offset,$query)"
            if (failing) Left(UserAdminError.Failure("boom"))
            else {
                val found = users.filter(matches(_, query))
                Right(UserPage(found.slice(offset, offset + limit), found.size))
            }
        }

        private def update(uid: String)(change: UserData => UserData): Either[UserAdminError, UserData] =
            if (failing) Left(UserAdminError.Failure("boom"))
            else users.find(_.firebaseUid == uid) match {
                case None => Left(UserAdminError.NotFound)
                case Some(u) =>
                    val updated = change(u)
                    users = users.map(x => if (x.firebaseUid == uid) updated else x)
                    Right(updated)
            }

        def setUserRole(uid: String, role: String): Either[UserAdminError, UserData] = {
            calls ::= s"setRole($uid,$role)"
            update(uid)(_.copy(roles = if (role == "Guest") Seq("Guest") else Seq("Guest", role)))
        }

        def setUserDeactivated(uid: String, deactivated: Boolean): Either[UserAdminError, UserData] = {
            calls ::= s"setDeactivated($uid,$deactivated)"
            update(uid)(_.copy(deactivated = deactivated))
        }
    }

    private def routesWith(store: UserAdminStore): Route = {
        val base: Route = Cors.preflight ~ Cors.addCorsHeaders {
            pathPrefix("api" / "users") {
                // The self-service route Server mounts before the administration ones.
                path("me" / "deactivate") {
                    post { stubAuthenticate { u => complete(s"self ${u.uid}") } }
                } ~
                UserAdminRoutes(stubAuthenticate, store)
            }
        }
        ErrorHandling.handleErrors { base ~ pathPrefix("api")(base) }
    }

    private def bearer(who: String) = RawHeader("Authorization", s"Bearer $who")
    private def json(text: String): HttpEntity.Strict = HttpEntity(ContentTypes.`application/json`, text)
    private def body: JsValue = parseAsClient(responseAs[String])
    private def errorCode: String = str(body, "error")
    private def fieldNames: Vector[String] = array(body, "fields").map(str(_, "field"))

    private val adminUserMembers = List("uid", "email", "name", "photo", "roles", "effectiveRole",
        "usageLimits", "deactivated", "createdAt", "updatedAt")

    // ── GET /api/users ──────────────────────────────────────────────────────────────────────────

    test("GET /api/users: 401 without a token, 403 for Administrator and Researcher, never hits the store") {
        val store = new StubStore
        Get("/api/users") ~> routesWith(store) ~> check {
            assertEquals(status, StatusCodes.Unauthorized)
            assertEquals(errorCode, "unauthorized")
        }
        Seq("admin", "researcher").foreach { who =>
            Get("/api/users") ~> bearer(who) ~> routesWith(store) ~> check {
                assertEquals(status, StatusCodes.Forbidden)
                assertEquals(errorCode, "forbidden")
                assertEquals(str(body, "message"), FirebaseAuthDirective.SuperAdminRequiredMessage)
            }
        }
        assertEquals(store.calls, Nil)
    }

    test("GET /api/users: the page, newest first, AdminUser members in order, defaults 50/0") {
        val store = new StubStore
        Get("/api/users") ~> bearer("super") ~> routesWith(store) ~> check {
            assertEquals(status, StatusCodes.OK)
            assert(header("Access-Control-Allow-Origin").isDefined, "CORS header")
            assertEquals(memberNames(body).toSet, Set("users", "total", "limit", "offset"))
            assert(responseAs[String].startsWith("""{"users":[{"uid":"uid-guest","email":"""), "member order")
            assertEquals(int(body, "total"), 4)
            assertEquals(int(body, "limit"), 50)
            assertEquals(int(body, "offset"), 0)
            val users = array(body, "users")
            assertEquals(users.map(str(_, "uid")), Vector("uid-guest", "uid-res", "uid-admin", "uid-super"))
            assertEquals(memberNames(users(0)).toSet, adminUserMembers.toSet)
            assertEquals(str(users(0), "effectiveRole"), "Guest")
            assertEquals(field(users(0), "deactivated"), JsTrue)
            assertEquals(int(field(users(0), "usageLimits"), "maxAgents"), 10)
            assertEquals(str(users(3), "effectiveRole"), "SuperAdministrator")
            assertEquals(field(field(users(3), "usageLimits"), "maxAgents"), JsNull)
            assertEquals(field(users(3), "photo"), JsNull)
            assertEquals(str(users(3), "createdAt"), "2026-10-31T10:00:00Z")
        }
        assertEquals(store.calls, List("list(50,0,)"))
    }

    test("GET /api/users: limit, offset and q (trimmed, case-insensitive) reach the store; also under /api/api") {
        val store = new StubStore
        Get("/api/users?limit=1&offset=1&q=%20RES%20") ~> bearer("super") ~> routesWith(store) ~> check {
            assertEquals(status, StatusCodes.OK)
            assertEquals(int(body, "total"), 1)
            assertEquals(int(body, "limit"), 1)
            assertEquals(int(body, "offset"), 1)
            assertEquals(array(body, "users"), Vector.empty) // the only match is on the first page
        }
        assertEquals(store.calls.head, "list(1,1,RES)")
        Get("/api/api/users?q=x.io") ~> bearer("super") ~> routesWith(store) ~> check {
            assertEquals(status, StatusCodes.OK)
            assertEquals(int(body, "total"), 4)
        }
    }

    test("GET /api/users: 400 invalid_body naming each bad paging parameter; malformed ones too") {
        val store = new StubStore
        Get("/api/users?limit=0") ~> bearer("super") ~> routesWith(store) ~> check {
            assertEquals(status, StatusCodes.BadRequest)
            assertEquals(errorCode, "invalid_body")
            assertEquals(fieldNames, Vector("limit"))
        }
        Get("/api/users?limit=201&offset=-1") ~> bearer("super") ~> routesWith(store) ~> check {
            assertEquals(status, StatusCodes.BadRequest)
            assertEquals(fieldNames, Vector("limit", "offset"))
        }
        Get("/api/users?q=" + "a".repeat(257)) ~> bearer("super") ~> routesWith(store) ~> check {
            assertEquals(status, StatusCodes.BadRequest)
            assertEquals(fieldNames, Vector("q"))
        }
        Get("/api/users?limit=abc") ~> bearer("super") ~> routesWith(store) ~> check {
            assertEquals(status, StatusCodes.BadRequest)
            assertEquals(errorCode, "invalid_body")
        }
        assertEquals(store.calls, Nil)
    }

    test("GET /api/users: a store failure is 500 internal_error without details") {
        Get("/api/users") ~> bearer("super") ~> routesWith(new StubStore(failing = true)) ~> check {
            assertEquals(status, StatusCodes.InternalServerError)
            assertEquals(errorCode, "internal_error")
            assert(!responseAs[String].contains("boom"))
        }
    }

    // ── GET /api/users/roles ────────────────────────────────────────────────────────────────────

    test("GET /api/users/roles: any authenticated user; five roles, highest priority first, with quotas") {
        Get("/api/users/roles") ~> routesWith(new StubStore) ~> check {
            assertEquals(status, StatusCodes.Unauthorized)
        }
        Get("/api/users/roles") ~> bearer("researcher") ~> routesWith(new StubStore) ~> check {
            assertEquals(status, StatusCodes.OK)
            val roles = array(body, "roles")
            assertEquals(roles.map(str(_, "role")),
                Vector("SuperAdministrator", "Administrator", "Researcher", "BaseUser", "Guest"))
            assertEquals(roles.map(int(_, "priority")), Vector(4, 3, 2, 1, 0))
            assertEquals(field(field(roles(0), "usageLimits"), "maxNetworks"), JsNull)
            assertEquals(int(field(roles(2), "usageLimits"), "maxAgents"), 1000)
            assertEquals(int(field(roles(4), "usageLimits"), "maxNetworks"), 1)
        }
    }

    // ── PUT /api/users/{uid}/role ───────────────────────────────────────────────────────────────

    test("PUT /api/users/{uid}/role: replaces the roles and answers the account") {
        val store = new StubStore
        Put("/api/users/uid-res/role", json("""{"role": "Administrator"}""")) ~> bearer("super") ~> routesWith(store) ~> check {
            assertEquals(status, StatusCodes.OK)
            assertEquals(memberNames(body).toSet, adminUserMembers.toSet)
            assert(responseAs[String].startsWith("""{"uid":"uid-res","email":"r@x.io","name":"""), "member order")
            assertEquals(str(body, "uid"), "uid-res")
            assertEquals(array(body, "roles"), Vector(JsString("Guest"), JsString("Administrator")))
            assertEquals(str(body, "effectiveRole"), "Administrator")
            assertEquals(field(field(body, "usageLimits"), "maxAgents"), JsNull)
        }
        assertEquals(store.calls, List("setRole(uid-res,Administrator)"))
        Put("/api/users/uid-res/role", json("""{"role": "Guest"}""")) ~> bearer("super") ~> routesWith(store) ~> check {
            assertEquals(status, StatusCodes.OK)
            assertEquals(array(body, "roles"), Vector(JsString("Guest")))
            assertEquals(int(field(body, "usageLimits"), "maxAgents"), 10)
        }
    }

    test("PUT /api/users/{uid}/role: 403 for non-super-admins and for the caller's own uid") {
        val store = new StubStore
        Put("/api/users/uid-res/role", json("""{"role": "Guest"}""")) ~> bearer("admin") ~> routesWith(store) ~> check {
            assertEquals(status, StatusCodes.Forbidden)
            assertEquals(str(body, "message"), FirebaseAuthDirective.SuperAdminRequiredMessage)
        }
        Put("/api/users/uid-super/role", json("""{"role": "Guest"}""")) ~> bearer("super") ~> routesWith(store) ~> check {
            assertEquals(status, StatusCodes.Forbidden)
            assertEquals(errorCode, "forbidden")
            assertEquals(str(body, "message"), UserAdminRoutes.OwnRoleMessage)
        }
        assertEquals(store.calls, Nil)
    }

    test("PUT /api/users/{uid}/role: 400 invalid_body on a bad body, 404 unknown uid, 500 store failure") {
        val store = new StubStore
        val routes = routesWith(store)
        Put("/api/users/uid-res/role", json("""{"role": "Owner"}""")) ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.BadRequest)
            assertEquals(errorCode, "invalid_body")
            assertEquals(fieldNames, Vector("role"))
            assert(str(array(body, "fields")(0), "issue").contains("SuperAdministrator, Administrator"))
        }
        Put("/api/users/uid-res/role", json("""{}""")) ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.BadRequest)
            assertEquals(str(array(body, "fields")(0), "issue"), "is required")
        }
        Put("/api/users/uid-res/role", json("""{"role": 3}""")) ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.BadRequest)
            assertEquals(str(array(body, "fields")(0), "issue"), "must be a string")
        }
        Put("/api/users/uid-res/role", json("""[1]""")) ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.BadRequest)
            assertEquals(fieldNames, Vector("role"))
        }
        Put("/api/users/uid-res/role", json("""{not json""")) ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.BadRequest)
            assertEquals(errorCode, "invalid_body")
        }
        Put("/api/users/uid-res/role", HttpEntity(ContentTypes.`text/plain(UTF-8)`, "Guest")) ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.UnsupportedMediaType)
        }
        assertEquals(store.calls, Nil)
        Put("/api/users/uid-nobody/role", json("""{"role": "Guest"}""")) ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.NotFound)
            assertEquals(errorCode, "not_found")
        }
        store.failing = true
        Put("/api/users/uid-res/role", json("""{"role": "Guest"}""")) ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.InternalServerError)
            assertEquals(errorCode, "internal_error")
        }
    }

    // ── POST /api/users/{uid}/deactivate | reactivate ───────────────────────────────────────────

    test("POST deactivate / reactivate: flips users.deactivated and answers the account") {
        val store = new StubStore
        val routes = routesWith(store)
        Post("/api/users/uid-res/deactivate") ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.OK)
            assertEquals(str(body, "uid"), "uid-res")
            assertEquals(field(body, "deactivated"), JsTrue)
            assertEquals(memberNames(body).toSet, adminUserMembers.toSet)
        }
        Post("/api/users/uid-guest/reactivate") ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.OK)
            assertEquals(field(body, "deactivated"), JsFalse)
        }
        assertEquals(store.calls.reverse, List("setDeactivated(uid-res,true)", "setDeactivated(uid-guest,false)"))
        Post("/api/users/uid-nobody/deactivate") ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.NotFound)
        }
        Post("/api/users/uid-nobody/reactivate") ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.NotFound)
        }
    }

    test("POST deactivate: 403 for non-super-admins and for the caller's own uid; `me` still is self-service") {
        val store = new StubStore
        val routes = routesWith(store)
        Post("/api/users/uid-res/deactivate") ~> bearer("researcher") ~> routes ~> check {
            assertEquals(status, StatusCodes.Forbidden)
        }
        Post("/api/users/uid-super/deactivate") ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.Forbidden)
            assertEquals(str(body, "message"), UserAdminRoutes.OwnDeactivateMessage)
        }
        Post("/api/users/me/deactivate") ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.OK)
            assertEquals(responseAs[String], "self uid-super")
        }
        assertEquals(store.calls, Nil)
        // reactivating oneself is pointless but harmless: an authenticated caller is not deactivated
        Post("/api/users/uid-super/reactivate") ~> bearer("super") ~> routes ~> check {
            assertEquals(status, StatusCodes.OK)
        }
    }

    test("wrong method on an administration path → 405 with Allow") {
        Get("/api/users/uid-res/deactivate") ~> bearer("super") ~> routesWith(new StubStore) ~> check {
            assertEquals(status, StatusCodes.MethodNotAllowed)
            assertEquals(errorCode, "method_not_allowed")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────

    test("pagingIssues and parseRole") {
        assertEquals(UserAdminRoutes.pagingIssues(50, 0, ""), Nil)
        assertEquals(UserAdminRoutes.pagingIssues(200, 0, "a".repeat(256)), Nil)
        assertEquals(UserAdminRoutes.pagingIssues(0, -1, "a".repeat(257)).map(_.field), List("limit", "offset", "q"))
        assertEquals(UserAdminRoutes.parseRole("""{"role":"BaseUser"}""".parseJson), Right("BaseUser"))
        assert(UserAdminRoutes.parseRole("""{"role":"baseuser"}""".parseJson).isLeft) // case matters
        assert(UserAdminRoutes.parseRole("""{"role":null}""".parseJson).isLeft)
        assert(UserAdminRoutes.parseRole(""""BaseUser"""".parseJson).isLeft)
    }
}
