package com.jax.assistant.ui.screens.knowledge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.db.FactEntity
import com.jax.assistant.executive.knowledge.BlockType
import com.jax.assistant.executive.knowledge.KnowledgeBlock
import com.jax.assistant.executive.knowledge.KnowledgePage
import com.jax.assistant.executive.knowledge.KnowledgeWorkspaceEngine
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.DarkCardBg
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark

@Composable
fun KnowledgeWorkspaceScreen(
    knowledgeEngine: KnowledgeWorkspaceEngine,
    facts: List<FactEntity> = emptyList()
) {
    val notebooks by knowledgeEngine.notebooks.collectAsState()
    val pages by knowledgeEngine.pages.collectAsState()

    var selectedPageId by remember(pages) {
        mutableStateOf(pages.firstOrNull()?.id ?: "")
    }

    val selectedPage = pages.find { it.id == selectedPageId } ?: pages.firstOrNull()

    var showNewPageDialog by remember { mutableStateOf(false) }
    var newPageTitle by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PureDark)
            .padding(16.dp)
    ) {
        // Workspace Header
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
                    text = "Notion-like block editor & document graph",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            // Quick migrate facts button
            Button(
                onClick = { knowledgeEngine.migratePlainNotesToPages(facts) },
                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = "Migrate Notes", tint = CyanAccent, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Migrate Notes", color = CyanAccent, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Page Selector Tabs
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(pages) { page ->
                val isSelected = page.id == selectedPage?.id
                Box(
                    modifier = Modifier
                        .background(
                            if (isSelected) CyanAccent.copy(alpha = 0.2f) else DarkCardBg,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) CyanAccent else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { selectedPageId = page.id }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            tint = if (isSelected) CyanAccent else Color.Gray,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = page.title,
                            color = if (isSelected) Color.White else Color.LightGray,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            item {
                IconButton(
                    onClick = { showNewPageDialog = true },
                    modifier = Modifier
                        .background(SurfaceDark, shape = RoundedCornerShape(8.dp))
                        .size(34.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Page", tint = CyanAccent)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (selectedPage != null) {
            // Render Active Page Block Editor
            Card(
                modifier = Modifier.fillMaxSize(),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Page Title
                    Text(
                        text = selectedPage.title,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Block Toolbar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        BlockTypeButton("Heading") { knowledgeEngine.addBlockToPage(selectedPage.id, BlockType.HEADING) }
                        BlockTypeButton("Text") { knowledgeEngine.addBlockToPage(selectedPage.id, BlockType.PARAGRAPH) }
                        BlockTypeButton("Checklist") { knowledgeEngine.addBlockToPage(selectedPage.id, BlockType.CHECKLIST) }
                        BlockTypeButton("Code") { knowledgeEngine.addBlockToPage(selectedPage.id, BlockType.CODE_BLOCK) }
                        BlockTypeButton("Quote") { knowledgeEngine.addBlockToPage(selectedPage.id, BlockType.QUOTE) }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Blocks List
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(selectedPage.blocks, key = { it.id }) { block ->
                            BlockItemView(
                                block = block,
                                onContentChange = { newText ->
                                    knowledgeEngine.updateBlockContent(selectedPage.id, block.id, newText)
                                },
                                onToggleCheck = { isChecked ->
                                    knowledgeEngine.updateBlockContent(selectedPage.id, block.id, block.content, isChecked)
                                },
                                onDelete = {
                                    knowledgeEngine.deleteBlock(selectedPage.id, block.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewPageDialog) {
        AlertDialog(
            onDismissRequest = { showNewPageDialog = false },
            title = { Text("Create Knowledge Page", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = newPageTitle,
                    onValueChange = { newPageTitle = it },
                    label = { Text("Page Title") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = Color.Gray
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPageTitle.isNotBlank()) {
                            val newPg = knowledgeEngine.createPage(
                                notebookId = notebooks.firstOrNull()?.id ?: "nb_executive_general",
                                title = newPageTitle.trim()
                            )
                            selectedPageId = newPg.id
                            newPageTitle = ""
                            showNewPageDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                ) {
                    Text("Create", color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewPageDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = DarkCardBg
        )
    }
}

@Composable
fun BlockTypeButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(DarkCardBg, shape = RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = "+ $label", color = CyanAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun BlockItemView(
    block: KnowledgeBlock,
    onContentChange: (String) -> Unit,
    onToggleCheck: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (block.type) {
            BlockType.HEADING -> {
                OutlinedTextField(
                    value = block.content,
                    onValueChange = onContentChange,
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(
                        color = CyanAccent,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    placeholder = { Text("Heading...", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            }
            BlockType.CHECKLIST -> {
                Checkbox(
                    checked = block.isChecked,
                    onCheckedChange = onToggleCheck,
                    colors = CheckboxDefaults.colors(checkedColor = CyanAccent)
                )
                OutlinedTextField(
                    value = block.content,
                    onValueChange = onContentChange,
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(
                        color = if (block.isChecked) Color.Gray else Color.White,
                        fontSize = 13.sp
                    ),
                    placeholder = { Text("Checklist item...", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            }
            BlockType.CODE_BLOCK -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(PureDark, shape = RoundedCornerShape(6.dp))
                        .padding(4.dp)
                ) {
                    OutlinedTextField(
                        value = block.content,
                        onValueChange = onContentChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(
                            color = Color(0xFF00FF99),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        placeholder = { Text("// Code block...", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                }
            }
            BlockType.QUOTE -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(DarkCardBg, shape = RoundedCornerShape(4.dp))
                        .padding(start = 8.dp)
                ) {
                    OutlinedTextField(
                        value = block.content,
                        onValueChange = onContentChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(
                            color = Color.LightGray,
                            fontSize = 13.sp
                        ),
                        placeholder = { Text("\" Quote block...\"", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanAccent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                }
            }
            else -> {
                OutlinedTextField(
                    value = block.content,
                    onValueChange = onContentChange,
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp),
                    placeholder = { Text("Paragraph...", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanAccent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            }
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Delete Block", tint = Color.Gray, modifier = Modifier.size(16.dp))
        }
    }
}
