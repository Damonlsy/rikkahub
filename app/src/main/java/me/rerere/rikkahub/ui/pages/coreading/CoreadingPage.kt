package me.rerere.rikkahub.ui.pages.coreading

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TopAppBarDefaults
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

private const val COREADING_URL = "https://rikkahub.local/assets/coreading/index.html"

/**
 * 一起读书：co-reading 原型 + App 助手关联。
 *
 * 页面通过 JS interface（window.Coreading）直接复用用户进来时所在的那个聊天框
 * （由入口带过来的 conversationId），不新开会话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoreadingPage(conversationId: String? = null) {
    val context = LocalContext.current.applicationContext
    val settingsStore: SettingsStore = koinInject()
    val chatService: ChatService = koinInject()
    val conversationRepo: ConversationRepository = koinInject()
    val memoryRepository: MemoryRepository = koinInject()
    val bridge = remember(conversationId) {
        CoreadingBridge(context, settingsStore, chatService, conversationRepo, memoryRepository, conversationId)
    }

    val state = rememberWebViewState(
        url = COREADING_URL,
        interfaces = mapOf("Coreading" to bridge),
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        },
    )

    BackHandler(state.canGoBack) {
        state.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(state.pageTitle?.takeIf { it.isNotEmpty() } ?: "一起读书")
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.topBarColors,
            )
        },
    ) {
        WebView(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(it),
        )
    }
}
