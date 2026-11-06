package preferences.backend

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.postgres.IPostgresSQL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import preferences.modeling.RoomSignals
import preferences.modeling.types.RoomId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val ROOM_CHANGES_CHANNEL = "chat_room_changes"

/** An empty payload is not a room: [PgRoomSignals.listen] sends it to learn that LISTEN is active. */
private const val PROBE = ""

/**
 * Signals a change of [roomId] to every server instance. Called in the transaction that makes the change,
 * PostgreSQL delivers the notification only after the commit and drops it on rollback.
 */
internal suspend fun QueryExecutor.notifyRoomChanged(roomId: RoomId) {
    currentExecutor().execute(
        Statement.create("SELECT pg_notify(:channel, :roomId)")
            .bind("channel", ROOM_CHANGES_CHANNEL)
            .bind("roomId", roomId.value),
    ).getOrThrow()
}

/**
 * Fans PostgreSQL notifications out to the readers of this server instance.
 *
 * One LISTEN connection serves every reader; LISTEN holds a connection, so a connection per reader would exhaust
 * the pool. Each notification only bumps the room's version and readers compare versions, so a burst of
 * notifications wakes a busy reader once.
 *
 * Notifications sent while the LISTEN connection is reconnecting are lost, and sqlx4k does not report reconnects.
 * Every reader therefore also wakes every [resyncInterval]: a lost notification delays a message by at most that
 * long instead of losing it.
 */
class PgRoomSignals private constructor(private val resyncInterval: Duration) : RoomSignals {
    // One map for every room: an update is a single atomic replacement, without locks.
    // Entries are never removed; the map grows with the number of rooms that changed while the server runs.
    private val versions = MutableStateFlow(mapOf<RoomId, Long>())

    override fun changes(roomId: RoomId): Flow<Unit> = merge(
        versions.map { it[roomId] }.distinctUntilChanged().map { },
        flow {
            while (true) {
                delay(resyncInterval)
                emit(Unit)
            }
        },
    ).conflate()

    private fun bump(roomId: RoomId) {
        versions.update { it + (roomId to (it[roomId] ?: 0) + 1) }
    }

    companion object {
        /**
         * Starts listening and returns once notifications arrive. sqlx4k starts LISTEN in the background,
         * so this sends probes until one comes back. The listener lives as long as the process.
         */
        suspend fun listen(database: IPostgresSQL, resyncInterval: Duration = 30.seconds): PgRoomSignals {
            val signals = PgRoomSignals(resyncInterval)
            val listening = CompletableDeferred<Unit>()
            database.listen(ROOM_CHANGES_CHANNEL) { notification ->
                when (val payload = notification.value.asStringOrNull().orEmpty()) {
                    PROBE -> listening.complete(Unit)
                    else -> signals.bump(RoomId(payload))
                }
            }
            withTimeout(10.seconds) {
                while (!listening.isCompleted) {
                    database.notify(ROOM_CHANGES_CHANNEL, PROBE)
                    withTimeoutOrNull(100.milliseconds) { listening.await() }
                }
            }
            return signals
        }
    }
}
