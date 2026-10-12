package app.lararium.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.lararium.service.AssistantState

@Composable
fun StateIndicator(state: AssistantState) {
    Text(
        text = when (state) {
            AssistantState.Idle -> "Ready"
            AssistantState.Listening -> "Listening..."
            AssistantState.Processing -> "Processing..."
            AssistantState.Searching -> "Searching..."
            AssistantState.Responding -> "Responding..."
            AssistantState.Speaking -> "Speaking..."
            AssistantState.Complete -> "Done"
            is AssistantState.Error -> "Error: ${state.message}"
        }
    )
}