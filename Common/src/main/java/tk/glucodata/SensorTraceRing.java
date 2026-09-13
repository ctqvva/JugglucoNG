package tk.glucodata;

/**
 * The last few hundred log lines, held in memory so the sensor connection log has something
 * to show when trace.log is switched off.
 *
 * <p>The drivers call {@link Log} unguarded and Kotlin string templates are eager, so the
 * message text is already built on every one of those calls whether or not logging is on --
 * today it is built and dropped. What this adds is four array stores and an index bump. It
 * allocates nothing per line, writes nothing to disk, and runs nothing in the background;
 * the line is only assembled when somebody opens the log and calls {@link #snapshot()}.
 *
 * <p>The standing cost is memory: the last {@link #CAPACITY} tag and message strings stay
 * reachable instead of being collected immediately, which is on the order of a hundred
 * kilobytes. Losing the buffer when the process dies is the trade for that -- trace.log is
 * what survives a restart.
 */
public final class SensorTraceRing {
	/**
	 * Deliberately larger than the 200 lines the log surfaces: these are unfiltered, and one
	 * sensor's lines are a fraction of what the app logs overall.
	 */
	private static final int CAPACITY = 400;

	/**
	 * Long enough for every line anyone reads -- a full CT5 reading line with its learned scale
	 * runs about a hundred characters. Without it one driver dumping a payload could pin
	 * megabytes here for as long as the process lives, which is the whole thing this is
	 * supposed not to do.
	 */
	private static final int MAX_MESSAGE_CHARS = 256;

	private static final Object lock = new Object();
	private static final long[] times = new long[CAPACITY];
	private static final char[] levels = new char[CAPACITY];
	private static final String[] tags = new String[CAPACITY];
	private static final String[] messages = new String[CAPACITY];
	private static int next = 0;
	private static boolean wrapped = false;

	/** Resolved once: it cannot change, and a snapshot would otherwise ask per line. */
	private static final String pid = Integer.toString(android.os.Process.myPid());

	private SensorTraceRing() {
	}

	/** Drops everything held. For tests, which share one process and one ring. */
	static void reset() {
		synchronized (lock) {
			next = 0;
			wrapped = false;
			java.util.Arrays.fill(tags, null);
			java.util.Arrays.fill(messages, null);
		}
	}

	/**
	 * @param level one of V/D/I/W/E, or a space when the caller logged without one.
	 * @param tag   the driver tag, or null for an untagged line.
	 */
	public static void add(char level, String tag, String message) {
		if (message == null) {
			return;
		}
		final long seconds = System.currentTimeMillis() / 1000L;
		// Only a line already over the cap pays for a copy, and the copy is the point: it lets
		// the original be collected instead of held for the next four hundred lines.
		final String kept = message.length() <= MAX_MESSAGE_CHARS
				? message
				: message.substring(0, MAX_MESSAGE_CHARS) + "…";
		synchronized (lock) {
			times[next] = seconds;
			levels[next] = level;
			tags[next] = tag;
			messages[next] = kept;
			if (++next == CAPACITY) {
				next = 0;
				wrapped = true;
			}
		}
	}

	/**
	 * Oldest first, in trace.log's own line shape -- {@code <epoch seconds> <pid> <level>/<tag>
	 * <message>} -- so one reader parses both sources.
	 */
	public static String[] snapshot() {
		final long[] whenAt;
		final char[] levelAt;
		final String[] tagAt;
		final String[] messageAt;
		final int start;
		final int count;
		synchronized (lock) {
			count = wrapped ? CAPACITY : next;
			if (count == 0) {
				return new String[0];
			}
			start = wrapped ? next : 0;
			whenAt = times.clone();
			levelAt = levels.clone();
			tagAt = tags.clone();
			messageAt = messages.clone();
		}
		final String[] out = new String[count];
		for (int i = 0; i < count; i++) {
			final int slot = (start + i) % CAPACITY;
			final StringBuilder sb = new StringBuilder(64);
			sb.append(whenAt[slot]).append(' ').append(pid).append(' ');
			if (levelAt[slot] != ' ' && tagAt[slot] != null) {
				sb.append(levelAt[slot]).append('/').append(tagAt[slot]).append(' ');
			} else if (tagAt[slot] != null) {
				sb.append(tagAt[slot]).append(' ');
			}
			sb.append(messageAt[slot]);
			out[i] = sb.toString();
		}
		return out;
	}
}
