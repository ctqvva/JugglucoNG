package tk.glucodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NightscoutTokenGrantTests {
    // The shape Nightscout answers with (taken from a real exchange; token shortened).
    private val body = """
        {"token":"eyJhbGciOi.eyJhY2Nlc3NUb2tlbiI.YrNG","sub":"aaps",
         "permissionGroups":[["api:treatments:create","api:treatments:read"],["api:devicestatus:create"]],
         "iat":1708854156,"exp":1708882956}
    """

    @Test
    fun theGrantCarriesTokenExpiryAndTheFlattenedPermissions() {
        val grant = NightscoutTokenGrant.parse(body)!!
        assertEquals("eyJhbGciOi.eyJhY2Nlc3NUb2tlbiI.YrNG", grant.token)
        // exp is in seconds on the wire.
        assertEquals(1708882956_000L, grant.expiresAtMillis)
        assertEquals(
            listOf("api:treatments:create", "api:treatments:read", "api:devicestatus:create"),
            grant.permissions
        )
    }

    @Test
    fun permissionsAreListedOnceEvenWhenTwoRolesGrantThem() {
        val grant = NightscoutTokenGrant.parse(
            """{"token":"t","exp":1,"permissionGroups":[["api:treatments:read"],["api:treatments:read","*"]]}"""
        )!!
        assertEquals(listOf("api:treatments:read", "*"), grant.permissions)
    }

    @Test
    fun anAdminTokenSaysStar() {
        val grant = NightscoutTokenGrant.parse("""{"token":"t","exp":1,"permissionGroups":[["*"],[]]}""")!!
        assertEquals(listOf("*"), grant.permissions)
    }

    @Test
    fun aRefusalIsNotAGrant() {
        assertNull(NightscoutTokenGrant.parse("""{"status":401,"message":"Unauthorized"}"""))
        assertNull(NightscoutTokenGrant.parse("<html>not nightscout</html>"))
        assertNull(NightscoutTokenGrant.parse(""))
    }

    @Test
    fun aGrantWithoutPermissionGroupsStillParses() {
        val grant = NightscoutTokenGrant.parse("""{"token":"t","exp":5}""")!!
        assertEquals(emptyList<String>(), grant.permissions)
        assertEquals(5_000L, grant.expiresAtMillis)
    }

    @Test
    fun theRefusalMessageIsTheServersOwnSentence() {
        assertEquals("Unauthorized", NightscoutTokenGrant.refusalMessage("""{"status":401,"message":"Unauthorized"}"""))
        assertEquals("Bad gateway", NightscoutTokenGrant.refusalMessage("Bad gateway"))
    }
}
