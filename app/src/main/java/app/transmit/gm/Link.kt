package app.transmit.gm

import kotlinx.coroutines.flow.StateFlow

/** The connection behind an inbox, as the inbox and chats show it: Google Messages ([GmBridge]), or the demo's. */
interface Link {
    val state: StateFlow<BridgeState>

    /** False until the conversation list has first arrived, so an empty one isn't mistaken for no chats. */
    val synced: StateFlow<Boolean>

    /** Makes this the active Messages for Web session again after another took over. */
    fun useHere()
}
