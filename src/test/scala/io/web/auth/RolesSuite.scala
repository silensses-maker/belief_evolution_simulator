package io.web.auth

/** The role catalog (migration 007): priority, effective role, quotas and the admin predicates. */
class RolesSuite extends munit.FunSuite {

    private def user(roles: String*): AuthenticatedUser =
        AuthenticatedUser("uid", "a@b.c", "A", None, roles.toSeq, 1)

    test("all: five roles, highest priority first, every one valid") {
        assertEquals(Roles.all, Seq("SuperAdministrator", "Administrator", "Researcher", "BaseUser", "Guest"))
        assertEquals(Roles.all.map(Roles.priority), Seq(4, 3, 2, 1, 0))
        assert(Roles.all.forall(Roles.isValid))
        assert(!Roles.isValid("admin"))
        assert(!Roles.isValid(""))
    }

    test("effective: the highest-priority known role; Guest for none or unknown") {
        assertEquals(Roles.effective(Seq.empty), "Guest")
        assertEquals(Roles.effective(Seq("Guest")), "Guest")
        assertEquals(Roles.effective(Seq("Guest", "Researcher")), "Researcher")
        assertEquals(Roles.effective(Seq("Researcher", "Guest", "Administrator")), "Administrator")
        assertEquals(Roles.effective(Seq("Administrator", "SuperAdministrator", "Guest")), "SuperAdministrator")
        assertEquals(Roles.effective(Seq("nope", "BaseUser")), "BaseUser")
        assertEquals(Roles.effective(Seq("nope")), "Guest")
    }

    test("usageLimits: SuperAdministrator and Administrator unlimited, the others as before") {
        val unlimited = UsageLimits(Int.MaxValue, Int.MaxValue, 1.0, Int.MaxValue)
        assertEquals(Roles.usageLimits(Seq("Guest", "SuperAdministrator")), unlimited)
        assertEquals(Roles.usageLimits(Seq("Guest", "Administrator")), unlimited)
        assertEquals(Roles.usageLimits(Seq("Guest", "Researcher")), UsageLimits(1000, 1000, 0.75, 20))
        assertEquals(Roles.usageLimits(Seq("Guest", "BaseUser")), UsageLimits(100, 100, 0.5, 5))
        assertEquals(Roles.usageLimits(Seq("Guest")), UsageLimits(10, 10, 0.5, 1))
        assertEquals(Roles.usageLimits(Seq.empty), UsageLimits(10, 10, 0.5, 1))
        assertEquals(Roles.limitsOf("SuperAdministrator"), Roles.Unlimited)
    }

    test("isAdmin / isSuperAdmin: a SuperAdministrator is an admin; an Administrator is not a super admin") {
        assert(user("Guest", "SuperAdministrator").isSuperAdmin)
        assert(user("Guest", "SuperAdministrator").isAdmin)
        assert(user("Guest", "Administrator").isAdmin)
        assert(!user("Guest", "Administrator").isSuperAdmin)
        assert(!user("Guest", "Researcher").isAdmin)
        assert(!user("Guest", "Researcher").isSuperAdmin)
        assert(!user().isAdmin)
    }
}
