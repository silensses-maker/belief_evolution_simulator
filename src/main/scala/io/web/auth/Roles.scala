package io.web.auth

/**
 * Per-role quotas. `Int.MaxValue` means unlimited and is serialized as `null` (ApiJson.jsQuota).
 * `maxNetworks` caps `numberOfNetworks` of generated runs (custom runs are always 1 network).
 */
final case class UsageLimits(maxAgents: Int, maxIterations: Int, densityFactor: Double, maxNetworks: Int)

/**
 * The roles of the `user_roles` table (Postgres enum `public.user_role`, migrations 002 and 007)
 * in ONE place: names, priority and quotas. An account holds a SET of roles — every account has
 * `Guest` (DatabaseManager.createOrUpdateUser) and promoted accounts keep it — and its effective
 * role is the one with the highest priority; the quotas are that role's.
 *
 *  - `SuperAdministrator`: everything an Administrator can do, unlimited quotas, and the only role
 *    that manages the other accounts — roles, deactivation, reactivation
 *    (`FirebaseAuthDirective.requireSuperAdmin`, `UserAdminRoutes`). The accounts listed in
 *    `BOOTSTRAP_ADMIN_EMAILS` are promoted to it on their first authenticated request.
 *  - `Administrator`: unlimited quotas, persistent frames, every run (`requireAdmin`, `isAdmin`).
 *  - `Researcher`, `BaseUser`, `Guest`: decreasing quotas.
 *
 * Kept free of database and HTTP imports so it is unit-tested without the connection pools.
 */
object Roles {
    final val SuperAdministrator = "SuperAdministrator"
    final val Administrator      = "Administrator"
    final val Researcher         = "Researcher"
    final val BaseUser           = "BaseUser"
    final val Guest              = "Guest"

    /** Every role, highest priority first. */
    val all: Seq[String] = Seq(SuperAdministrator, Administrator, Researcher, BaseUser, Guest)

    val priority: Map[String, Int] = Map(
        SuperAdministrator -> 4, Administrator -> 3, Researcher -> 2, BaseUser -> 1, Guest -> 0
    )

    def isValid(role: String): Boolean = priority.contains(role)

    val Unlimited: UsageLimits = UsageLimits(Int.MaxValue, Int.MaxValue, 1.0, Int.MaxValue)

    def limitsOf(role: String): UsageLimits = role match {
        case SuperAdministrator | Administrator => Unlimited
        case Researcher                         => UsageLimits(1000, 1000, 0.75, 20)
        case BaseUser                           => UsageLimits(100, 100, 0.5, 5)
        case _                                  => UsageLimits(10, 10, 0.5, 1)
    }

    /** The role that decides the quotas: the highest-priority known one; `Guest` for none. */
    def effective(roles: Seq[String]): String =
        roles.filter(isValid).maxByOption(priority).getOrElse(Guest)

    def usageLimits(roles: Seq[String]): UsageLimits = limitsOf(effective(roles))
}
