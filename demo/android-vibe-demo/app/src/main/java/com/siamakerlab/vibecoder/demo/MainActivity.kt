package com.siamakerlab.vibecoder.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DemoApp()
        }
    }
}

@Composable
private fun DemoApp() {
    MaterialTheme {
        Surface(color = Color(0xFFF7F8FA), modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Vibe Coder Demo",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D2430),
                )
                Text(
                    text = "Minimal Android project for validating the server Android toolchain and Mistral Vibe ACP runtime.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF4A5568),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(modifier = Modifier.height(24.dp))
                StatusCard(label = "Gradle project", value = "Ready")
                Spacer(modifier = Modifier.height(12.dp))
                StatusCard(label = "Target SDK", value = "35")
                Spacer(modifier = Modifier.height(12.dp))
                StatusCard(label = "Agent runtime", value = "mistral-vibe-acp")
            }
        }
    }
}

@Composable
private fun StatusCard(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, color = Color(0xFF4A5568))
            Text(text = value, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F766E))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DemoAppPreview() {
    DemoApp()
}
