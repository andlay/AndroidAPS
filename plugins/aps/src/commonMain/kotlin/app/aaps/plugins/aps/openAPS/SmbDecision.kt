package app.aaps.plugins.aps.openAPS

/**
 * The log line that says why SMBs are switched on or off in this run, for example
 * "SMB disabled by Full Loop logic: iob 9.3 is above effective iobTH 7.5". The algorithms write it
 * to their console logs while they decide; the last one written is the one that counts.
 *
 * @return null when no such line was written
 */
fun smbDecisionLine(vararg logs: List<String>): String? =
    logs.asList().flatten().lastOrNull { it.startsWith("SMB enabled") || it.startsWith("SMB disabled") }
