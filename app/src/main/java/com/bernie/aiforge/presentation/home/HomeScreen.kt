package com.bernie.aiforge.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bernie.aiforge.data.db.ChatDao
import com.bernie.aiforge.data.db.ChatEntity
import com.bernie.aiforge.skills.Skill
import com.bernie.aiforge.skills.SkillRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val skillRegistry: SkillRegistry,
    private val chatDao: ChatDao,
) : ViewModel() {

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    val recentChats: StateFlow<List<ChatEntity>> = chatDao.getAllChats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            skillRegistry.reload()
            _skills.value = skillRegistry.getAll()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            skillRegistry.reload()
            _skills.value = skillRegistry.getAll()
        }
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bernie.aiforge.data.db.ChatEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChat: (chatId: String?, skillId: String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMemory: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val skills      by viewModel.skills.collectAsState()
    val recentChats by viewModel.recentChats.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Forge", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenMemory)  { Icon(Icons.Default.Psychology, "Memory") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("New chat") },
                icon = { Icon(Icons.Default.Add, null) },
                onClick = { onOpenChat(null, "default") },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(bottom = 80.dp),
            modifier       = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Quick action bar ─────────────────────────────────────────────
            item {
                Row(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickActionChip(
                        label = "History",
                        icon  = Icons.Default.History,
                        onClick = onOpenHistory,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionChip(
                        label = "Skills",
                        icon  = Icons.Default.Extension,
                        onClick = onOpenSkills,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── Skills horizontal scroll ──────────────────────────────────
            item {
                SectionHeader("Skills", onSeeAll = onOpenSkills)
                LazyRow(
                    contentPadding      = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(skills) { skill ->
                        SkillCard(skill) { onOpenChat(null, skill.id) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Recent chats ─────────────────────────────────────────────
            if (recentChats.isNotEmpty()) {
                item { SectionHeader("Recent", onSeeAll = onOpenHistory) }
                items(recentChats.take(5)) { chat ->
                    RecentChatRow(chat) { onOpenChat(chat.id, chat.skillId) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, onSeeAll: () -> Unit) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = onSeeAll) { Text("See all") }
    }
}

@Composable
private fun SkillCard(skill: com.bernie.aiforge.skills.Skill, onClick: () -> Unit) {
    ElevatedCard(
        onClick   = onClick,
        modifier  = Modifier.width(140.dp),
        elevation = CardDefaults.elevatedCardElevation(2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(skill.emoji, style = MaterialTheme.typography.headlineSmall)
            Text(skill.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(skill.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline, maxLines = 2)
        }
    }
}

@Composable
private fun RecentChatRow(chat: ChatEntity, onClick: () -> Unit) {
    ListItem(
        headlineContent  = { Text(chat.title, maxLines = 1) },
        supportingContent = {
            Text(
                java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(chat.updatedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        },
        leadingContent   = { Icon(Icons.Default.ChatBubbleOutline, null, tint = MaterialTheme.colorScheme.primary) },
        modifier         = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun QuickActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(onClick = onClick, modifier = modifier) {
        Row(
            modifier            = Modifier.padding(10.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
