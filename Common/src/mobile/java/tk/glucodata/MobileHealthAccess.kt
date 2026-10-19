package tk.glucodata

/**
 * The phone's adapters for [HealthConnectAccess] and [HealthPermissionsAccess]
 * (direction.md §4, category P). Both classes stay in this source set; only the entry
 * points shared code named are behind a registry.
 */
object MobileHealthConnect : HealthConnect {
    override fun start(activity: MainActivity) {
        HealthConnection.init(activity)
    }

    override fun stop() {
        HealthConnection.stop()
    }

    override fun writeAll(sensorPtr: Long, sensorName: String) {
        HealthConnection.writeAll(sensorPtr, sensorName)
    }
}

object MobileHealthPermissions : HealthPermissionsAccess.Factory {
    override fun create(activity: MainActivity): HealthPermissionRequester {
        val launch = LaunchShit(activity)
        return HealthPermissionRequester { permissions ->
            launch.permissionsLauncher.launch(permissions)
        }
    }
}
