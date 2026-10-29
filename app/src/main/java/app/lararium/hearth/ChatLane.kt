package app.lararium.hearth

/** Pure decision: is the chat lane usable? Unit-testable, no Android imports. */
sealed interface ChatLane {
    data object Ready : ChatLane
    data object NotPaired : ChatLane
}

fun chatLaneFor(hasEndpoint: Boolean): ChatLane =
    if (hasEndpoint) ChatLane.Ready else ChatLane.NotPaired
