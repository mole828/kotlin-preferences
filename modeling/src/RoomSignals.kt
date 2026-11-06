package preferences.modeling

import kotlinx.coroutines.flow.Flow
import preferences.modeling.types.RoomId

/**
 * Wakes readers of a room. A signal only says "look again": it carries no data, and signals may be merged.
 * Readers query the repositories for what changed, so a merged or late signal delays a reader but never loses data.
 */
interface RoomSignals {
    /**
     * Emits once on collection, then after the room's messages or memberships change.
     * A change is signalled only once committed, so a rolled-back change never wakes a reader.
     */
    fun changes(roomId: RoomId): Flow<Unit>
}
