package com.jax.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.GoldAccent
import com.jax.assistant.ui.theme.JAXAssistantTheme
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark

data class KnowledgeDocument(
    val id: String,
    val title: String,
    val category: String,
    val tags: List<String>,
    val content: String,
    val updatedAt: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeWorkspaceScreen(
    documents: List<KnowledgeDocument> = emptyList(),
    onSearchQueryChange: (String) -> Unit = {},
    onAddDocument: (title: String, category: String, content: String) -> Unit = { _, _, _ -> }
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    val categories = listOf("All", "Research", "Code & Specs", "Personal Notes", "Executive")

    val filteredDocs = remember(documents, searchQuery, selectedCategory) {
        documents.filter { doc ->
            val matchesCat = selectedCategory == "All" || doc.category.equals(selectedCategory, ignoreCase = true)
            val matchesQuery = searchQuery.isBlank() || doc.title.contains(searchQuery, ignoreCase = true) || doc.content.contains(searchQuery, ignoreCase = true)
            matchesCat && matchesQuery
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Knowledge Workspace",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Structured AI repository & project memory",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Doc", tint = PureDark)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "New Doc", color = PureDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Search Bar
            TextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onSearchQueryChange(it)
                },
                placeholder = { Text("Search docs, code snippets, specs...", fontSize = 12.sp, color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = CyanAccent) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Category Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    val isSelected = selectedCategory == cat
                    Surface(
                        onClick = { selectedCategory = cat },
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) CyanAccent else SurfaceDark
                    ) {
                        Text(
                            text = cat,
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Documents List
            if (filteredDocs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No knowledge items found.\nTap '+ New Doc' to store research or notes.",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredDocs, key = { it.id }) { doc ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Article,
                                            contentDescription = "Document",
                                            tint = CyanAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = doc.title,
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = doc.category,
                                        color = GoldAccent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = doc.content,
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    maxLines = 3
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        doc.tags.forEach { tag ->
                                            Surface(
                                                color = CyanAccent.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = "#$tag",
                                                    color = CyanAccent,
                                                    fontSize = 10.sp,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "Updated: ${doc.updatedAt}",
                                        color = Color.Gray,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Knowledge Workspace Screen Preview")
@Composable
fun KnowledgeWorkspaceScreenPreview() {
    val sampleDocs = listOf(
        KnowledgeDocument(
            id = "1",
            title = "J.A.X. AI Router Architecture Specification",
            category = "Code & Specs",
            tags = listOf("AI", "Architecture", "Gemini"),
            content = "The AIRouter manages automated fallback between gemini-2.0-flash, gemini-1.5-pro, and local heuristics with exponential backoff and cooldown tracking.",
            updatedAt = "Today, 07:15 AM"
        ),
        KnowledgeDocument(
            id = "2",
            title = "Q3 Marketing Strategy & KPI Goals",
            category = "Research",
            tags = listOf("Strategy", "Q3", "Growth"),
            content = "Targeting 45% increase in user retention through voice-first assistant workflows and instant task scheduling automation.",
            updatedAt = "Yesterday"
        ),
        KnowledgeDocument(
            id = "3",
            title = "Database Migration & Room Schema Notes",
            category = "Code & Specs",
            tags = listOf("Room", "SQLite", "Android"),
            content = "Entity definitions for TaskEntity, FactEntity, and RequestLogEntity with automated timestamp indices.",
            updatedAt = "3 days ago"
        )
    )

    JAXAssistantTheme {
        KnowledgeWorkspaceScreen(documents = sampleDocs)
    }
}
