package com.jax.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark

@Composable
fun VisionScreen(
    analysis: String,
    isAnalyzing: Boolean,
    onChooseImage: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(PureDark).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = CyanAccent)
        Spacer(Modifier.height(12.dp))
        Text("Vision", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onChooseImage,
            enabled = !isAnalyzing,
            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
        ) {
            Text(if (isAnalyzing) "Analyzing..." else "Choose Image", color = PureDark)
        }
        if (isAnalyzing) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(color = CyanAccent)
        }
        if (analysis.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            Text(analysis, color = Color.White, modifier = Modifier.fillMaxWidth().background(SurfaceDark).padding(16.dp))
        }
    }
}