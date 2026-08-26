package com.jax.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.GoldAccent
import com.jax.assistant.ui.theme.JAXAssistantTheme
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark

data class ComposeChatMessage(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniChatScreen(
    messages: List<ComposeChatMessage>,
    onSendMessage: (String) -> Unit,
    onMicClick: () -> Unit,
    voiceDraft: String = "",
    isListening: Boolean = false,
    conversationMode: Boolean = false,
    onToggleConversationMode: () -> Unit = {},
    onNewChat: () -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(voiceDraft) {
        if (voiceDraft.isNotBlank()) inputText = voiceDraft
    }

    // Pulsing animation for the mic while actively listening.
    val pulse = rememberInfiniteTransition(label = "micPulse")
    val micScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "micScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onNewChat) {
                Icon(Icons.Default.Add, contentDescription = "New Chat", tint = CyanAccent, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Chat", color = CyanAccent, fontSize = 12.sp)
            }
        }
        // Chat Messages List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = 280.dp)
                            .clip(
                                RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = if (msg.isUser) 16.dp else 4.dp,
                                    bottomEnd = if (msg.isUser) 4.dp else 16.dp
                                )
                            )
                            .background(if (msg.isUser) CyanAccent.copy(alpha = 0.2f) else SurfaceDark)
                            .padding(14.dp)
                    ) {
                        Text(
                            text = msg.text,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Listening banner (voice overlay indicator)
        AnimatedVisibility(visible = isListening) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyanAccent.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = GoldAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (conversationMode) "Listening (hands-free)\u2026" else "Listening\u2026",
                    color = GoldAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Bottom Input Field
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onMicClick,
                modifier = Modifier
                    .size(42.dp)
                    .scale(if (isListening) micScale else 1f)
                    .background(
                        if (isListening) GoldAccent.copy(alpha = 0.25f) else CyanAccent.copy(alpha = 0.15f),
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = if (isListening) "Stop listening" else "Voice Input",
                    tint = if (isListening) GoldAccent else CyanAccent
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onToggleConversationMode,
                modifier = Modifier
                    .size(42.dp)
                    .background(
                        if (conversationMode) CyanAccent.copy(alpha = 0.30f) else Color.Transparent,
                        CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.RecordVoiceOver,
                    contentDescription = "Hands-free conversation mode",
                    tint = if (conversationMode) CyanAccent else Color.Gray
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Talk or type to J.A.X...", fontSize = 13.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = PureDark,
                    unfocusedContainerColor = PureDark,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSendMessage(inputText)
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(42.dp)
                    .background(CyanAccent, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    tint = Color.Black
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "OmniChat Screen Preview")
@Composable
fun OmniChatScreenPreview() {
    val sampleMessages = listOf(
        ComposeChatMessage(
            id = "1",
            text = "Hello J.A.X., can you summarize my schedule for today?",
            isUser = true,
            timestamp = "09:00 AM"
        ),
        ComposeChatMessage(
            id = "2",
            text = "Good morning! You have 3 tasks due today: Finalize Q3 budget at 10:00 AM, Team sync at 2:00 PM, and Code review at 4:30 PM.",
            isUser = false,
            timestamp = "09:01 AM"
        ),
        ComposeChatMessage(
            id = "3",
            text = "Great! Please set a reminder for the budget meeting.",
            isUser = true,
            timestamp = "09:02 AM"
        )
    )

    JAXAssistantTheme {
        OmniChatScreen(
            messages = sampleMessages,
            onSendMessage = {},
            onMicClick = {}
        )
    }
}
