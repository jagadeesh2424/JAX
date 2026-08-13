package com.jax.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jax.assistant.db.BlockType
import com.jax.assistant.db.NoteBlockEntity
import com.jax.assistant.db.NotePageEntity
import com.jax.assistant.ui.theme.CyanAccent
import com.jax.assistant.ui.theme.GoldAccent
import com.jax.assistant.ui.theme.JAXAssistantTheme
import com.jax.assistant.ui.theme.PureDark
import com.jax.assistant.ui.theme.SurfaceDark

@Composable
fun KnowledgeWorkspaceScreen(
    pages: List<NotePageEntity>,
    selectedPageId: String?,
    blocks: List<NoteBlockEntity>,
    noteSearchResults: List<NotePageEntity> = emptyList(),
    relatedPages: List<NotePageEntity> = emptyList(),
    onOpenPage: (String) -> Unit,
    onClosePage: () -> Unit,
    onSearchNotes: (String) -> Unit = {},
    onCreatePage: (title: String, category: String, tags: String) -> Unit,
    onRenamePage: (NotePageEntity, String) -> Unit,
    onUpdatePageTags: (NotePageEntity, String) -> Unit = { _, _ -> },
    onDeletePage: (NotePageEntity) -> Unit,
    onAddBlock: (type: String, afterBlockId: String?) -> Unit,
    onUpdateBlockContent: (NoteBlockEntity, String) -> Unit,
    onToggleBlockChecked: (NoteBlockEntity) -> Unit,
    onChangeBlockType: (NoteBlockEntity, String) -> Unit,
    onDeleteBlock: (NoteBlockEntity) -> Unit,
    onLinkPage: (String) -> Unit = {},
    onUnlinkPage: (String) -> Unit = {},
    focusBlockId: String? = null,
    onFocusHandled: () -> Unit = {}
) {
    val openPage = remember(selectedPageId, pages) { pages.find { it.id == selectedPageId } }
    if (openPage != null) {
        BlockEditor(
            page = openPage,
            blocks = blocks,
            allPages = pages,
            relatedPages = relatedPages,
            onBack = onClosePage,
            onOpenPage = onOpenPage,
            onRenamePage = onRenamePage,
            onUpdatePageTags = onUpdatePageTags,
            onDeletePage = onDeletePage,
            onAddBlock = onAddBlock,
            onUpdateBlockContent = onUpdateBlockContent,
            onToggleBlockChecked = onToggleBlockChecked,
            onChangeBlockType = onChangeBlockType,
            onDeleteBlock = onDeleteBlock,
            onLinkPage = onLinkPage,
            onUnlinkPage = onUnlinkPage,
            focusBlockId = focusBlockId,
            onFocusHandled = onFocusHandled
        )
    } else {
        PageList(
            pages = pages,
            noteSearchResults = noteSearchResults,
            onSearchNotes = onSearchNotes,
            onOpenPage = onOpenPage,
            onCreatePage = onCreatePage,
            onDeletePage = onDeletePage
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageList(
    pages: List<NotePageEntity>,
    noteSearchResults: List<NotePageEntity>,
    onSearchNotes: (String) -> Unit,
    onOpenPage: (String) -> Unit,
    onCreatePage: (title: String, category: String, tags: String) -> Unit,
    onDeletePage: (NotePageEntity) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showNewDialog by remember { mutableStateOf(false) }

    // When searching, show DB-backed results (title/category/tags + block content); otherwise all pages.
    val displayed = if (searchQuery.isBlank()) pages else noteSearchResults

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
                        text = "Notion-style editable notes",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = { showNewDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Page", tint = PureDark)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "New Page", color = PureDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            TextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onSearchNotes(it)
                },
                placeholder = { Text("Search title, tags, content...", fontSize = 12.sp, color = Color.Gray) },
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

            Spacer(modifier = Modifier.height(16.dp))

            if (displayed.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isBlank())
                            "No pages yet.\nTap 'New Page' to start writing."
                        else
                            "No matches for \"$searchQuery\".",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(displayed, key = { it.id }) { page ->
                        Card(
                            onClick = { onOpenPage(page.id) },
                            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Article,
                                        contentDescription = "Page",
                                        tint = CyanAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(page.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        Text(page.category, color = GoldAccent, fontSize = 11.sp)
                                        TagChips(page.tags)
                                    }
                                }
                                IconButton(onClick = { onDeletePage(page) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewPageDialog(
            onDismiss = { showNewDialog = false },
            onConfirm = { title, category, tags ->
                onCreatePage(title, category, tags)
                showNewDialog = false
            }
        )
    }
}

@Composable
private fun TagChips(tags: String) {
    val parsed = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (parsed.isEmpty()) return
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        parsed.take(4).forEach { tag ->
            Box(
                modifier = Modifier
                    .background(CyanAccent.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("#$tag", color = CyanAccent, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun NewPageDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, category: String, tags: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Personal Notes") }
    var tags by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(title, category, tags) }) {
                Text("Create", color = CyanAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
        },
        title = { Text("New Page", color = Color.White) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags (comma-separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        containerColor = SurfaceDark
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockEditor(
    page: NotePageEntity,
    blocks: List<NoteBlockEntity>,
    allPages: List<NotePageEntity>,
    relatedPages: List<NotePageEntity>,
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    onRenamePage: (NotePageEntity, String) -> Unit,
    onUpdatePageTags: (NotePageEntity, String) -> Unit,
    onDeletePage: (NotePageEntity) -> Unit,
    onAddBlock: (type: String, afterBlockId: String?) -> Unit,
    onUpdateBlockContent: (NoteBlockEntity, String) -> Unit,
    onToggleBlockChecked: (NoteBlockEntity) -> Unit,
    onChangeBlockType: (NoteBlockEntity, String) -> Unit,
    onDeleteBlock: (NoteBlockEntity) -> Unit,
    onLinkPage: (String) -> Unit,
    onUnlinkPage: (String) -> Unit,
    focusBlockId: String?,
    onFocusHandled: () -> Unit
) {
    var titleText by remember(page.id) { mutableStateOf(page.title) }
    var tagsText by remember(page.id) { mutableStateOf(page.tags) }
    var showLinkPicker by remember(page.id) { mutableStateOf(false) }
    var activeBlockId by remember(page.id) { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (titleText.trim() != page.title) onRenamePage(page, titleText.trim())
                    if (tagsText.trim() != page.tags) onUpdatePageTags(page, tagsText.trim())
                    onBack()
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = CyanAccent)
                }
                TextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    placeholder = { Text("Page title", color = Color.Gray, fontSize = 20.sp) },
                    textStyle = LocalTextStyle.current.copy(
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = CyanAccent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onDeletePage(page) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete page", tint = Color.Red)
                }
            }

            TextField(
                value = tagsText,
                onValueChange = {
                    tagsText = it
                    onUpdatePageTags(page, it)
                },
                placeholder = { Text("Add tags (comma-separated)", color = Color.Gray, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Tag, contentDescription = "Tags", tint = CyanAccent, modifier = Modifier.size(16.dp)) },
                textStyle = LocalTextStyle.current.copy(color = CyanAccent, fontSize = 12.sp),
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = CyanAccent
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Divider(color = SurfaceDark)

            RelatedPagesSection(
                relatedPages = relatedPages,
                onOpenPage = { id ->
                    if (titleText.trim() != page.title) onRenamePage(page, titleText.trim())
                    if (tagsText.trim() != page.tags) onUpdatePageTags(page, tagsText.trim())
                    onOpenPage(id)
                },
                onUnlinkPage = onUnlinkPage,
                onAddLink = { showLinkPicker = true }
            )

            Divider(color = SurfaceDark)

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(blocks, key = { it.id }) { block ->
                    BlockRow(
                        block = block,
                        onUpdateContent = { onUpdateBlockContent(block, it) },
                        onToggleChecked = { onToggleBlockChecked(block) },
                        onChangeType = { onChangeBlockType(block, it) },
                        onDelete = { onDeleteBlock(block) },
                        onEnter = { onAddBlock(block.type, block.id) },
                        onFocused = { activeBlockId = block.id },
                        shouldFocus = block.id == focusBlockId,
                        onFocusHandled = onFocusHandled
                    )
                }
            }

            Divider(color = SurfaceDark)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AddBlockChip("Text", Icons.Default.Notes) { onAddBlock(BlockType.TEXT, activeBlockId) }
                AddBlockChip("Heading", Icons.Default.Title) { onAddBlock(BlockType.HEADING, activeBlockId) }
                AddBlockChip("Bullet", Icons.Default.FormatListBulleted) { onAddBlock(BlockType.BULLET, activeBlockId) }
                AddBlockChip("To-do", Icons.Default.CheckBox) { onAddBlock(BlockType.CHECKLIST, activeBlockId) }
                AddBlockChip("Code", Icons.Default.Code) { onAddBlock(BlockType.CODE, activeBlockId) }
                AddBlockChip("Quote", Icons.Default.FormatQuote) { onAddBlock(BlockType.QUOTE, activeBlockId) }
                AddBlockChip("Divider", Icons.Default.HorizontalRule) { onAddBlock(BlockType.DIVIDER, activeBlockId) }
            }
        }

        if (showLinkPicker) {
            val linkedIds = remember(relatedPages) { relatedPages.map { it.id }.toSet() }
            val candidates = remember(allPages, linkedIds, page.id) {
                allPages.filter { it.id != page.id && it.id !in linkedIds }
            }
            LinkPickerDialog(
                candidates = candidates,
                onDismiss = { showLinkPicker = false },
                onPick = { id ->
                    onLinkPage(id)
                    showLinkPicker = false
                }
            )
        }
    }
}

@Composable
private fun RelatedPagesSection(
    relatedPages: List<NotePageEntity>,
    onOpenPage: (String) -> Unit,
    onUnlinkPage: (String) -> Unit,
    onAddLink: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Default.Hub,
            contentDescription = "Related pages",
            tint = GoldAccent,
            modifier = Modifier.size(16.dp)
        )
        if (relatedPages.isEmpty()) {
            Text("No linked pages", color = Color.Gray, fontSize = 11.sp)
        } else {
            relatedPages.forEach { related ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SurfaceDark
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Text(
                            related.title.ifBlank { "Untitled" },
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { onOpenPage(related.id) }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Unlink",
                            tint = Color.Gray,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onUnlinkPage(related.id) }
                        )
                    }
                }
            }
        }
        Surface(
            onClick = onAddLink,
            shape = RoundedCornerShape(10.dp),
            color = SurfaceDark
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Link page", tint = CyanAccent, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(2.dp))
                Text("Link", color = CyanAccent, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun LinkPickerDialog(
    candidates: List<NotePageEntity>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Link a page", color = Color.White) },
        text = {
            if (candidates.isEmpty()) {
                Text("No other pages to link.", color = Color.Gray, fontSize = 13.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(candidates, key = { it.id }) { candidate ->
                        Surface(
                            onClick = { onPick(candidate.id) },
                            shape = RoundedCornerShape(8.dp),
                            color = PureDark,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    candidate.title.ifBlank { "Untitled" },
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                if (candidate.category.isNotBlank()) {
                                    Text(candidate.category, color = CyanAccent, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = CyanAccent) }
        }
    )
}

@Composable
private fun AddBlockChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = SurfaceDark
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Icon(icon, contentDescription = label, tint = CyanAccent, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(label, color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
private fun BlockRow(
    block: NoteBlockEntity,
    onUpdateContent: (String) -> Unit,
    onToggleChecked: () -> Unit,
    onChangeType: (String) -> Unit,
    onDelete: () -> Unit,
    onEnter: () -> Unit = {},
    onFocused: () -> Unit = {},
    shouldFocus: Boolean = false,
    onFocusHandled: () -> Unit = {}
) {
    // A divider block is purely visual: render a rule with just a delete affordance.
    if (block.type == BlockType.DIVIDER) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Divider(color = Color.Gray, modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(16.dp))
            }
        }
        return
    }

    var text by remember(block.id) { mutableStateOf(block.content) }
    var menuExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(shouldFocus) {
        if (shouldFocus) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
            onFocusHandled()
        }
    }

    val fontSize = if (block.type == BlockType.HEADING) 20.sp else 15.sp
    val placeholder = when (block.type) {
        BlockType.HEADING -> "Heading"
        BlockType.BULLET -> "List item"
        BlockType.CHECKLIST -> "To-do"
        BlockType.CODE -> "// code"
        BlockType.QUOTE -> "Quote"
        else -> "Type here..."
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (block.type) {
            BlockType.CHECKLIST -> Checkbox(
                checked = block.checked,
                onCheckedChange = { onToggleChecked() },
                colors = CheckboxDefaults.colors(checkedColor = CyanAccent)
            )
            BlockType.BULLET -> Text(
                "\u2022",
                color = CyanAccent,
                fontSize = 18.sp,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp)
            )
            BlockType.QUOTE -> Box(
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp)
                    .width(3.dp)
                    .height(24.dp)
                    .background(CyanAccent)
            )
        }

        val fieldModifier = if (block.type == BlockType.CODE) {
            Modifier
                .weight(1f)
                .background(Color(0xFF0A0E14), RoundedCornerShape(6.dp))
        } else {
            Modifier.weight(1f)
        }

        // Bullet/checklist rows behave like list items: Enter creates the next item.
        val isListItem = block.type == BlockType.BULLET || block.type == BlockType.CHECKLIST

        TextField(
            value = text,
            onValueChange = {
                text = it
                onUpdateContent(it)
            },
            singleLine = isListItem,
            keyboardOptions = if (isListItem) KeyboardOptions(imeAction = ImeAction.Next) else KeyboardOptions.Default,
            keyboardActions = if (isListItem) KeyboardActions(onNext = {
                if (text.isBlank()) onChangeType(BlockType.TEXT) else onEnter()
            }) else KeyboardActions.Default,
            placeholder = { Text(placeholder, color = Color.Gray, fontSize = fontSize) },
            textStyle = LocalTextStyle.current.copy(
                color = if (block.type == BlockType.QUOTE) Color(0xFFBBBBBB) else Color.White,
                fontSize = if (block.type == BlockType.CODE) 13.sp else fontSize,
                fontWeight = if (block.type == BlockType.HEADING) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (block.type == BlockType.CODE) FontFamily.Monospace else FontFamily.Default,
                fontStyle = if (block.type == BlockType.QUOTE) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if (block.type == BlockType.CHECKLIST && block.checked) TextDecoration.LineThrough else null
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = CyanAccent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = fieldModifier
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onFocused() }
        )

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Block options", tint = Color.Gray)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Text") }, onClick = { onChangeType(BlockType.TEXT); menuExpanded = false })
                DropdownMenuItem(text = { Text("Heading") }, onClick = { onChangeType(BlockType.HEADING); menuExpanded = false })
                DropdownMenuItem(text = { Text("Bullet") }, onClick = { onChangeType(BlockType.BULLET); menuExpanded = false })
                DropdownMenuItem(text = { Text("To-do") }, onClick = { onChangeType(BlockType.CHECKLIST); menuExpanded = false })
                DropdownMenuItem(text = { Text("Code") }, onClick = { onChangeType(BlockType.CODE); menuExpanded = false })
                DropdownMenuItem(text = { Text("Quote") }, onClick = { onChangeType(BlockType.QUOTE); menuExpanded = false })
                Divider()
                DropdownMenuItem(text = { Text("Delete", color = Color.Red) }, onClick = { onDelete(); menuExpanded = false })
            }
        }
    }
}

@Preview(showBackground = true, name = "Knowledge Workspace Screen Preview")
@Composable
fun KnowledgeWorkspaceScreenPreview() {
    val samplePages = listOf(
        NotePageEntity(id = "1", title = "Q3 Strategy", category = "Executive"),
        NotePageEntity(id = "2", title = "Room Schema Notes", category = "Code & Specs")
    )

    JAXAssistantTheme {
        KnowledgeWorkspaceScreen(
            pages = samplePages,
            selectedPageId = null,
            blocks = emptyList(),
            onOpenPage = {},
            onClosePage = {},
            onCreatePage = { _, _, _ -> },
            onRenamePage = { _, _ -> },
            onDeletePage = {},
            onAddBlock = { _, _ -> },
            onUpdateBlockContent = { _, _ -> },
            onToggleBlockChecked = {},
            onChangeBlockType = { _, _ -> },
            onDeleteBlock = {}
        )
    }
}
