package com.sri.androidmentorchat.feature.chat.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sri.androidmentorchat.core.database.MentorDatabase
import com.sri.androidmentorchat.core.database.MessageEntity
import com.sri.androidmentorchat.core.model.Agent
import com.sri.androidmentorchat.core.model.AgentType
import com.sri.androidmentorchat.core.model.ChatHistory
import com.sri.androidmentorchat.core.model.ChatSession
import com.sri.androidmentorchat.core.model.DifficultyLevel
import com.sri.androidmentorchat.core.model.SenderType
import com.sri.androidmentorchat.feature.chat.data.GeminiRepository
import com.sri.androidmentorchat.feature.chat.domain.ChatHistoryRepository
import com.sri.androidmentorchat.feature.chat.domain.ChatRunner
import com.sri.androidmentorchat.feature.moltbook.data.MoltbookRepository
import com.sri.androidmentorchat.feature.moltbook.domain.CreatePostUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class AndroidMentorChatViewModel(
    private val geminiRepository: GeminiRepository,
    private val chatHistoryRepository: ChatHistoryRepository,
    private val createPostUseCase: CreatePostUseCase
) : ViewModel() {

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application)
                val geminiRepository = GeminiRepository()
                val chatHistoryRepository = ChatHistoryRepository(
                    MentorDatabase.getDatabase(application).messageDao()
                )
                val createPostUseCase = CreatePostUseCase(MoltbookRepository())
                AndroidMentorChatViewModel(
                    geminiRepository,
                    chatHistoryRepository,
                    createPostUseCase
                )
            }
        }
    }

    private val _selectedAgent: MutableStateFlow<Agent> =
        MutableStateFlow(AgentType.ANDROID_MENTOR.agent)
    val selectedAgent: StateFlow<Agent> = _selectedAgent.asStateFlow()

    private val _difficultyLevel: MutableStateFlow<DifficultyLevel?> =
        MutableStateFlow(DifficultyLevel.BEGINNER)
    val difficultyLevel: StateFlow<DifficultyLevel?> = _difficultyLevel.asStateFlow()

    private val _chatSession = MutableStateFlow(
        ChatSession(
            chatId = UUID.randomUUID().toString(),
            messages = listOf()
        )
    )
    val chatSession = _chatSession.asStateFlow()

    var chatRunner: ChatRunner = ChatRunner(
        repository = geminiRepository,
        agent = _selectedAgent.value
    )

    private val _isChatClearable = MutableStateFlow(true)
    val isChatClearable: StateFlow<Boolean> = _isChatClearable.asStateFlow()

    private val _shareDialogVisible = MutableStateFlow(false)
    val shareDialogVisible: StateFlow<Boolean> = _shareDialogVisible.asStateFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            val dbMessages = chatHistoryRepository.getAllMessages().first()
            if (dbMessages.isNotEmpty()) {
                val history = dbMessages.map {
                    ChatHistory(SenderType.valueOf(it.sender), it.message)
                }
                _chatSession.update { it.copy(messages = history) }
            }
        }
    }

    fun selectAgent(agentType: AgentType) {
        _selectedAgent.value = agentType.agent
        _difficultyLevel.value =
            if (!agentType.agent.supportsDifficulty) null else DifficultyLevel.BEGINNER
        _isChatClearable.value = agentType.agent == AgentType.ANDROID_MENTOR.agent
        _chatSession.value = ChatSession(
            chatId = UUID.randomUUID().toString(),
            messages = listOf()
        )
        if (agentType == AgentType.ANDROID_MENTOR) {
            loadHistory()
        }
        chatRunner = ChatRunner(
            repository = geminiRepository,
            agent = agentType.agent
        )
    }

    fun selectDifficulty(level: DifficultyLevel) {
        if (_difficultyLevel.value != level) {
            _difficultyLevel.value = level
            _chatSession.value = ChatSession(
                chatId = UUID.randomUUID().toString(),
                messages = listOf()
            )
        }
    }

    fun updateChatSession(chatHistoryList: List<ChatHistory>) {
        _chatSession.update {
            it.copy(messages = chatHistoryList)
        }
    }

    fun sendMessage(prompt: String) {
        val userMessage = ChatHistory(senderType = SenderType.USER, message = prompt)
        _chatSession.update { it.copy(messages = it.messages + userMessage) }
        if (_selectedAgent.value == AgentType.ANDROID_MENTOR.agent)
            saveMessage(text = prompt, sender = SenderType.USER.name)

        val responseBuilder = StringBuilder()
        viewModelScope.launch {
            _chatSession.update {
                it.copy(
                    messages = it.messages + ChatHistory(
                        senderType = SenderType.GEMINI,
                        message = "Thinking..."
                    )
                )
            }

            chatRunner.streamResponse(
                session = _chatSession.value,
                difficultyLevel = _difficultyLevel.value
            ).collect { chunk ->
                if (chunk.isNotEmpty()) {
                    responseBuilder.append(chunk)
                    _chatSession.update { session ->
                        val newList = session.messages.toMutableList()
                        val lastIndex = newList.lastIndex
                        if (lastIndex >= 0 && newList[lastIndex].senderType == SenderType.GEMINI) {
                            val currentMessage = newList[lastIndex].message
                            newList[lastIndex] =
                                if (currentMessage == "Thinking...")
                                    newList[lastIndex].copy(message = chunk)
                                else
                                    newList[lastIndex].copy(message = currentMessage + chunk)
                        }
                        session.copy(messages = newList)
                    }
                }
            }
            val response = responseBuilder.toString()
            if (_selectedAgent.value == AgentType.ANDROID_MENTOR.agent)
                saveMessage(text = response, sender = SenderType.GEMINI.name)
        }
    }

    fun clearChatSession() {
        viewModelScope.launch {
            chatHistoryRepository.clearChat()
            _chatSession.value = ChatSession(
                chatId = UUID.randomUUID().toString(),
                messages = listOf()
            )
        }
    }

    val messages = chatHistoryRepository.getAllMessages()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
            initialValue = emptyList()
        )

    fun saveMessage(text: String, sender: String) {
        viewModelScope.launch {
            val messageEntity = MessageEntity(
                message = text,
                sender = sender,
                timeStamp = System.currentTimeMillis()
            )
            chatHistoryRepository.insertMessages(messageEntity)
        }
    }

    fun shareChatToMoltbook(snackBarContent: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val summary = chatRunner.getGeminiSummary(
                    session = _chatSession.value
                )
                Log.d("TAG", "shareChatToMoltbook: summary = $summary")

                createPostUseCase.createPost(
                    title = "Android Mentor Chat",
                    content = summary
                )

                snackBarContent("Post created successfully")
            } catch (e: Exception) {
                Log.e("Moltbook", "Failed to share", e)

                snackBarContent("Failed to share")
            }
        }
    }

    fun showShareDialog() {
        _shareDialogVisible.value = true
    }

    fun dismissShareDialog() {
        _shareDialogVisible.value = false
    }
}