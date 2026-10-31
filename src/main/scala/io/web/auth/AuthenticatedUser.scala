package io.web.auth

case class AuthenticatedUser(
    uid: String,
    email: String,
    name: String,
    photo: Option[String],
    roles: Seq[String],
    dbUserId: Int
) {
    def hasRole(role: String): Boolean = roles.contains(role)

    /** The only role that manages other accounts (roles, deactivation): see [[Roles]]. */
    def isSuperAdmin: Boolean = hasRole(Roles.SuperAdministrator)

    /** Administrator abilities (persistent frames, every run, other users' info); a SuperAdministrator has them all. */
    def isAdmin: Boolean = hasRole(Roles.Administrator) || isSuperAdmin
}
