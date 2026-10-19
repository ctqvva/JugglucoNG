package tk.glucodata;

/** Pure launch ordering used by Notify and unit tests. */
final class AlarmLaunchStrategy {
    private AlarmLaunchStrategy() {
    }

    static boolean shouldAttemptDirectStart() {
        return true;
    }

    static boolean shouldQueueBackup(boolean directStartIsReliable) {
        return !directStartIsReliable;
    }
}
