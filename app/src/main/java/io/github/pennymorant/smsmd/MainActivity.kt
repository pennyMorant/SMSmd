package io.github.pennymorant.smsmd

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pennymorant.smsmd.core.settings.SmtpProfile
import io.github.pennymorant.smsmd.core.settings.SmtpProvider
import io.github.pennymorant.smsmd.core.settings.SmtpSecurity
import io.github.pennymorant.smsmd.core.settings.ThemeMode
import io.github.pennymorant.smsmd.core.settings.AppSettings
import io.github.pennymorant.smsmd.core.settings.DeliveryEngine
import io.github.pennymorant.smsmd.core.settings.defaults
import io.github.pennymorant.smsmd.core.telegram.TelegramChat
import io.github.pennymorant.smsmd.core.telegram.TelegramClient
import io.github.pennymorant.smsmd.update.AppUpdateState
import io.github.pennymorant.smsmd.update.AppUpdateViewModel
import io.github.pennymorant.smsmd.ui.theme.SmsMdTheme
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel> {
        MainViewModel.Factory((application as SmsMdApplication).container)
    }
    private val updateViewModel by viewModels<AppUpdateViewModel> {
        AppUpdateViewModel.Factory((application as SmsMdApplication).container.updateRepository)
    }
    private var hasSmsPermission by mutableStateOf(false)
    private var hasNotificationPermission by mutableStateOf(false)
    private var hasBackgroundAccess by mutableStateOf(false)

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasSmsPermission = granted }
    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotificationPermission = granted
        if (granted) viewModel.setFailureNotifications(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissions()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val updateState by updateViewModel.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (state.settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            SmsMdTheme(themeMode = state.settings.themeMode) {
                SmsMdApp(
                    viewModel = viewModel,
                    hasSmsPermission = hasSmsPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    hasBackgroundAccess = hasBackgroundAccess,
                    updateState = updateState,
                    requestSmsPermission = {
                        smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                    },
                    requestNotificationPermission = {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    changeBackgroundAccess = ::changeBackgroundAccess,
                    openSystemSettings = {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:$packageName".toUri(),
                            ),
                        )
                    },
                    checkForUpdate = updateViewModel::checkForUpdate,
                    openUpdate = ::openUpdate,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        hasSmsPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECEIVE_SMS,
        ) == PackageManager.PERMISSION_GRANTED
        hasNotificationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        hasBackgroundAccess = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
    }

    private fun openUpdate(releaseUrl: String) {
        startActivity(Intent(Intent.ACTION_VIEW, releaseUrl.toUri()))
    }

    @SuppressLint("BatteryLife")
    private fun changeBackgroundAccess(enabled: Boolean) {
        val action = if (enabled) {
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        } else {
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        }
        val intent = Intent(action).apply {
            if (enabled) data = "package:$packageName".toUri()
        }
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

}

private enum class AppTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

private enum class StatusTone { SUCCESS, WARNING, ERROR, INFO, NEUTRAL }

private enum class ProfileSavePhase { IDLE, SAVING, SUCCESS, FAILED }

private data class ProfileEditorState(
    val profile: SmtpProfile,
    val password: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmsMdApp(
    viewModel: MainViewModel,
    hasSmsPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasBackgroundAccess: Boolean,
    updateState: AppUpdateState,
    requestSmsPermission: () -> Unit,
    requestNotificationPermission: () -> Unit,
    changeBackgroundAccess: (Boolean) -> Unit,
    openSystemSettings: () -> Unit,
    checkForUpdate: () -> Unit,
    openUpdate: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var showSmsDisclosure by rememberSaveable { mutableStateOf(false) }
    var showProfileTypePicker by rememberSaveable { mutableStateOf(false) }
    var editingTelegram by rememberSaveable { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ProfileEditorState?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        snackbar.currentSnackbarData?.dismiss()
        scope.launch { snackbar.showSnackbar(message) }
    }

    if (showSmsDisclosure) {
        PermissionDisclosure(
            dismiss = { showSmsDisclosure = false },
            continueRequest = {
                showSmsDisclosure = false
                requestSmsPermission()
            },
        )
    }

    editingProfile?.let { editorState ->
        ModalBottomSheet(
            onDismissRequest = { editingProfile = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            sheetGesturesEnabled = false,
            dragHandle = null,
        ) {
            ProfileEditor(
                initial = editorState.profile,
                initialPassword = editorState.password,
                testStatus = state.testStatus,
                save = viewModel::saveProfile,
                test = { draft, password -> viewModel.testProfile(draft, password, showMessage) },
            )
        }
    }

    if (showProfileTypePicker) {
        ModalBottomSheet(
            onDismissRequest = { showProfileTypePicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            DeliveryTypePicker(
                selectEmail = {
                    showProfileTypePicker = false
                    editingProfile = ProfileEditorState(newProfile(SmtpProvider.GMAIL), "")
                },
                selectTelegram = {
                    showProfileTypePicker = false
                    editingTelegram = true
                },
            )
        }
    }

    if (editingTelegram) {
        ModalBottomSheet(
            onDismissRequest = { editingTelegram = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            sheetGesturesEnabled = false,
            dragHandle = null,
        ) {
            TelegramEditor(
                settings = state.settings,
                testStatus = state.telegramTestStatus,
                loadToken = viewModel::loadTelegramToken,
                save = viewModel::saveTelegram,
                test = { token, chatId -> viewModel.testTelegram(token, chatId, showMessage) },
                detectChats = viewModel::detectTelegramChats,
            )
        }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(selectedTab.label) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (selectedTab == AppTab.SETTINGS) {
                FloatingActionButton(
                    onClick = { showProfileTypePicker = true },
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add delivery profile")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                AppTab.HOME -> HomeScreen(
                    state = state,
                    hasSmsPermission = hasSmsPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    hasBackgroundAccess = hasBackgroundAccess,
                    setEnabled = { enabled ->
                        if (enabled && !hasSmsPermission) {
                            selectedTab = AppTab.SETTINGS
                            showMessage("Grant SMS permission in Settings")
                        } else {
                            viewModel.setEnabled(enabled, showMessage)
                        }
                    },
                )
                AppTab.SETTINGS -> SettingsScreen(
                    state = state,
                    hasSmsPermission = hasSmsPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    hasBackgroundAccess = hasBackgroundAccess,
                    updateState = updateState,
                    changeSmsPermission = { enabled ->
                        if (enabled) showSmsDisclosure = true else openSystemSettings()
                    },
                    changeNotifications = { enabled ->
                        if (enabled && !hasNotificationPermission) requestNotificationPermission()
                        else viewModel.setFailureNotifications(enabled)
                    },
                    changeBackgroundAccess = changeBackgroundAccess,
                    changeTheme = viewModel::setThemeMode,
                    editProfile = { profile ->
                        viewModel.loadSmtpPassword(profile.id) { password ->
                            editingProfile = ProfileEditorState(profile, password)
                        }
                    },
                    selectProfile = { viewModel.selectProfile(it, showMessage) },
                    deleteProfile = { viewModel.deleteProfile(it, showMessage) },
                    editTelegram = { editingTelegram = true },
                    selectTelegram = { viewModel.selectTelegram(showMessage) },
                    checkForUpdate = checkForUpdate,
                    openUpdate = openUpdate,
                )
            }
        }
    }
}

@Composable
private fun PermissionDisclosure(dismiss: () -> Unit, continueRequest: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Allow incoming SMS access") },
        text = {
            Text(
                "SMSmd reads new incoming messages only so it can forward them through your active " +
                    "delivery profile. Message content is encrypted locally and removed after delivery.",
            )
        },
        confirmButton = { TextButton(onClick = continueRequest) { Text("Continue") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Not now") } },
    )
}

@Composable
private fun HomeScreen(
    state: MainUiState,
    hasSmsPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasBackgroundAccess: Boolean,
    setEnabled: (Boolean) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("SMSmd", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Forward incoming messages through email or Telegram.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SummaryCard(
                title = "Forwarding",
                detail = if (state.settings.forwardingEnabled) "Incoming SMS will be forwarded" else "Paused",
                trailing = {
                    Switch(
                        checked = state.settings.forwardingEnabled,
                        onCheckedChange = setEnabled,
                    )
                },
            )
        }
        item {
            val ready = hasSmsPermission && hasNotificationPermission && hasBackgroundAccess
            SummaryCard("Permissions", trailing = {
                StatusPill(if (ready) "Ready" else "Not ready", if (ready) StatusTone.SUCCESS else StatusTone.WARNING)
            })
        }
        item {
            val isTelegram = state.settings.activeEngine == DeliveryEngine.TELEGRAM
            val profile = state.activeProfile
            val ready = if (isTelegram) {
                state.settings.telegramVerified && state.telegramCredentialReady
            } else {
                profile?.smtpVerified == true && profile.id in state.smtpCredentialProfileIds
            }
            SummaryCard(
                title = "Delivery profile",
                detail = if (isTelegram) "Telegram Bot" else profile?.name ?: "No profile selected",
                trailing = {
                    StatusPill(
                        text = if (ready) "Ready" else "Not ready",
                        tone = if (ready) StatusTone.SUCCESS else StatusTone.WARNING,
                    )
                },
            )
        }
        item {
            val last = state.lastDelivery
            val sent = last?.result == "SENT"
            SummaryCard(
                title = "Last delivery",
                detail = last?.let {
                    "${it.channelSummary}\n${DateFormat.getDateTimeInstance().format(Date(it.completedAt))}"
                }
                    ?: "No delivery yet",
                trailing = {
                    StatusPill(
                        text = when {
                            last == null -> "None"
                            sent -> "Sent"
                            last.result == "PARTIAL" -> "Partial"
                            else -> "Failed"
                        },
                        tone = when {
                            last == null -> StatusTone.NEUTRAL
                            sent -> StatusTone.SUCCESS
                            last.result == "PARTIAL" -> StatusTone.WARNING
                            else -> StatusTone.ERROR
                        },
                    )
                },
            )
        }
        if (state.pendingCount > 0) {
            item {
                SummaryCard("Pending delivery", trailing = {
                    StatusPill(state.pendingCount.toString(), StatusTone.INFO)
                })
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    detail: String? = null,
    trailing: @Composable () -> Unit,
) {
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            trailing()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    state: MainUiState,
    hasSmsPermission: Boolean,
    hasNotificationPermission: Boolean,
    hasBackgroundAccess: Boolean,
    updateState: AppUpdateState,
    changeSmsPermission: (Boolean) -> Unit,
    changeNotifications: (Boolean) -> Unit,
    changeBackgroundAccess: (Boolean) -> Unit,
    changeTheme: (ThemeMode) -> Unit,
    editProfile: (SmtpProfile) -> Unit,
    selectProfile: (SmtpProfile) -> Unit,
    deleteProfile: (SmtpProfile) -> Unit,
    editTelegram: () -> Unit,
    selectTelegram: () -> Unit,
    checkForUpdate: () -> Unit,
    openUpdate: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = state.settings.themeMode == mode,
                                onClick = { changeTheme(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                                label = { Text(themeModeLabel(mode)) },
                            )
                        }
                    }
                }
            }
        }
        item {
            AppUpdateCard(
                state = updateState,
                checkForUpdate = checkForUpdate,
                openUpdate = openUpdate,
            )
        }
        item {
            Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    PermissionRow(
                        title = "Incoming SMS",
                        detail = "Required for forwarding",
                        granted = hasSmsPermission,
                        onCheckedChange = changeSmsPermission,
                    )
                    HorizontalDivider()
                    PermissionRow(
                        title = "Failure notifications",
                        detail = "Notify when delivery fails",
                        granted = hasNotificationPermission && state.settings.failureNotifications,
                        onCheckedChange = changeNotifications,
                    )
                    HorizontalDivider()
                    PermissionRow(
                        title = "Background operation",
                        detail = "Allow reliable forwarding while the app is closed",
                        granted = hasBackgroundAccess,
                        onCheckedChange = changeBackgroundAccess,
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Delivery profiles", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("${state.profiles.size + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            TelegramProfileCard(
                settings = state.settings,
                credentialReady = state.telegramCredentialReady,
                active = state.settings.activeEngine == DeliveryEngine.TELEGRAM,
                select = selectTelegram,
                edit = editTelegram,
            )
        }
        items(state.profiles, key = { it.id }) { profile ->
            ProfileCard(
                profile = profile,
                credentialReady = profile.id in state.smtpCredentialProfileIds,
                active = state.settings.activeEngine == DeliveryEngine.EMAIL &&
                    profile.id == state.settings.activeProfileId,
                testStatus = state.testStatus,
                select = { selectProfile(profile) },
                edit = { editProfile(profile) },
                delete = { deleteProfile(profile) },
            )
        }
    }
}

@Composable
private fun DeliveryTypePicker(
    selectEmail: () -> Unit,
    selectTelegram: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Add delivery profile", style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(onClick = selectTelegram, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Telegram")
        }
        OutlinedButton(onClick = selectEmail, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Email, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Email")
        }
    }
}

@Composable
private fun TelegramProfileCard(
    settings: AppSettings,
    credentialReady: Boolean,
    active: Boolean,
    select: () -> Unit,
    edit: () -> Unit,
) {
    val ready = settings.telegramVerified && credentialReady
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = active, onClick = select, enabled = ready)
            Column(Modifier.weight(1f)) {
                Text("Telegram Bot", style = MaterialTheme.typography.titleMedium)
                Text(
                    settings.telegramChatId.ifBlank { "Not configured" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = if (ready) "Ready" else "Not ready",
                tone = if (ready) StatusTone.SUCCESS else StatusTone.WARNING,
            )
            FilledTonalIconButton(onClick = edit) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit Telegram configuration")
            }
        }
    }
}

@Composable
private fun TelegramEditor(
    settings: AppSettings,
    testStatus: TelegramTestStatus,
    loadToken: ((String) -> Unit) -> Unit,
    save: (String, String, (Boolean, String) -> Unit) -> Unit,
    test: (String, String) -> Unit,
    detectChats: (String, (Result<List<TelegramChat>>) -> Unit) -> Unit,
) {
    var token by rememberSaveable { mutableStateOf("") }
    var chatId by rememberSaveable { mutableStateOf(settings.telegramChatId) }
    var savePhase by rememberSaveable { mutableStateOf(ProfileSavePhase.IDLE) }
    var saveError by rememberSaveable { mutableStateOf("") }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var detecting by rememberSaveable { mutableStateOf(false) }
    var detectError by rememberSaveable { mutableStateOf("") }
    var detectedChats by remember { mutableStateOf<List<TelegramChat>>(emptyList()) }
    var showChats by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { loadToken { token = it } }
    LaunchedEffect(testStatus.phase) {
        if (testStatus.phase == TelegramTestPhase.SUCCESS) dirty = false
    }

    if (showChats) {
        AlertDialog(
            onDismissRequest = { showChats = false },
            title = { Text("Select Telegram chat") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.5f)) {
                    items(detectedChats, key = { it.id }) { chat ->
                        TextButton(
                            onClick = {
                                chatId = chat.id
                                dirty = true
                                showChats = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(chat.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${chat.type} · ${chat.id}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Telegram configuration", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            val verified = settings.telegramVerified && token.isNotBlank() && !dirty
            StatusPill(
                text = when {
                    testStatus.phase == TelegramTestPhase.TESTING -> "Testing"
                    testStatus.phase == TelegramTestPhase.FAILED -> "Failed"
                    verified -> "Ready"
                    else -> "Not ready"
                },
                tone = when {
                    testStatus.phase == TelegramTestPhase.TESTING -> StatusTone.INFO
                    testStatus.phase == TelegramTestPhase.FAILED -> StatusTone.ERROR
                    verified -> StatusTone.SUCCESS
                    else -> StatusTone.WARNING
                },
            )
        }
        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                dirty = true
            },
            label = { Text("Bot token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = chatId,
            onValueChange = {
                chatId = it
                dirty = true
            },
            label = { Text("Chat ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                detecting = true
                detectError = ""
                detectChats(token) { result ->
                    detecting = false
                    result.onSuccess {
                        detectedChats = it
                        showChats = true
                    }.onFailure { detectError = TelegramClient.safeError(it) }
                }
            },
            enabled = !detecting && testStatus.phase != TelegramTestPhase.TESTING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (detecting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Sync, contentDescription = null)
            }
            Spacer(Modifier.size(8.dp))
            Text(if (detecting) "Detecting" else "Detect chat")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    savePhase = ProfileSavePhase.SAVING
                    saveError = ""
                    save(token, chatId) { success, message ->
                        savePhase = if (success) ProfileSavePhase.SUCCESS else ProfileSavePhase.FAILED
                        if (success) dirty = false
                        else saveError = message
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = savePhase != ProfileSavePhase.SAVING &&
                    testStatus.phase != TelegramTestPhase.TESTING,
            ) { SaveButtonContent(savePhase) }
            Button(
                onClick = { test(token, chatId) },
                modifier = Modifier.weight(1f),
                enabled = savePhase != ProfileSavePhase.SAVING &&
                    testStatus.phase != TelegramTestPhase.TESTING,
            ) { TelegramTestButtonContent(testStatus.phase) }
        }
        if (detectError.isNotBlank()) {
            Text(detectError, color = MaterialTheme.colorScheme.error)
        }
        if (saveError.isNotBlank()) {
            Text(saveError, color = MaterialTheme.colorScheme.error)
        }
        if (testStatus.phase == TelegramTestPhase.FAILED && testStatus.message.isNotBlank()) {
            Text(testStatus.message, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AppUpdateCard(
    state: AppUpdateState,
    checkForUpdate: () -> Unit,
    openUpdate: (String) -> Unit,
) {
    val status = when (state) {
        AppUpdateState.Idle -> "Version ${BuildConfig.VERSION_NAME}"
        AppUpdateState.Checking -> "Checking GitHub Releases"
        is AppUpdateState.UpToDate -> "Version ${state.version} is up to date"
        is AppUpdateState.UpdateAvailable -> "Version ${state.version} is available"
        is AppUpdateState.Failed -> state.message
    }
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("App updates", style = MaterialTheme.typography.titleMedium)
                    Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (state) {
                    AppUpdateState.Checking ->
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    is AppUpdateState.UpdateAvailable -> Button(onClick = { openUpdate(state.releaseUrl) }) {
                        Text("View")
                    }
                    else -> OutlinedButton(onClick = checkForUpdate) {
                        Text(if (state is AppUpdateState.Failed) "Try again" else "Check")
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    granted: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusPill(if (granted) "Ready" else "Required", if (granted) StatusTone.SUCCESS else StatusTone.WARNING)
        Switch(checked = granted, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ProfileCard(
    profile: SmtpProfile,
    credentialReady: Boolean,
    active: Boolean,
    testStatus: SmtpTestStatus,
    select: () -> Unit,
    edit: () -> Unit,
    delete: () -> Unit,
) {
    val ready = profile.smtpVerified && credentialReady
    val phase = if (testStatus.profileId == profile.id) testStatus.phase else SmtpTestPhase.IDLE
    val testing = phase == SmtpTestPhase.TESTING
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                RadioButton(selected = active, onClick = select, enabled = ready)
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${providerLabel(profile.provider)} · ${profile.smtpUsername.ifBlank { "Account required" }}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    text = when {
                        testing -> "Testing"
                        phase == SmtpTestPhase.FAILED -> "Failed"
                        ready -> "Ready"
                        else -> "Not ready"
                    },
                    tone = when {
                        testing -> StatusTone.INFO
                        phase == SmtpTestPhase.FAILED -> StatusTone.ERROR
                        ready -> StatusTone.SUCCESS
                        else -> StatusTone.WARNING
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = edit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit SMTP profile")
                }
                FilledTonalIconButton(
                    onClick = delete,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete SMTP profile")
                }
            }
            if (phase == SmtpTestPhase.FAILED && testStatus.message.isNotBlank()) {
                Text(testStatus.message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TestButtonContent(phase: SmtpTestPhase) {
    AnimatedContent(targetState = phase, label = "SMTP test status") { current ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (current) {
                SmtpTestPhase.IDLE -> Icon(Icons.Outlined.Email, contentDescription = null)
                SmtpTestPhase.TESTING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                SmtpTestPhase.SUCCESS -> Icon(Icons.Outlined.Check, contentDescription = null)
                SmtpTestPhase.FAILED -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            }
            Text(
                when (current) {
                    SmtpTestPhase.IDLE -> "Test"
                    SmtpTestPhase.TESTING -> "Testing"
                    SmtpTestPhase.SUCCESS -> "Verified"
                    SmtpTestPhase.FAILED -> "Retry"
                },
            )
        }
    }
}

@Composable
private fun TelegramTestButtonContent(phase: TelegramTestPhase) {
    AnimatedContent(targetState = phase, label = "Telegram test status") { current ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (current) {
                TelegramTestPhase.IDLE -> Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                TelegramTestPhase.TESTING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TelegramTestPhase.SUCCESS -> Icon(Icons.Outlined.Check, contentDescription = null)
                TelegramTestPhase.FAILED -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            }
            Text(
                when (current) {
                    TelegramTestPhase.IDLE -> "Test"
                    TelegramTestPhase.TESTING -> "Testing"
                    TelegramTestPhase.SUCCESS -> "Verified"
                    TelegramTestPhase.FAILED -> "Retry"
                },
            )
        }
    }
}

@Composable
private fun SaveButtonContent(phase: ProfileSavePhase) {
    AnimatedContent(targetState = phase, label = "SMTP save status") { current ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (current) {
                ProfileSavePhase.IDLE -> Icon(Icons.Outlined.Save, contentDescription = null)
                ProfileSavePhase.SAVING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                ProfileSavePhase.SUCCESS -> Icon(Icons.Outlined.Check, contentDescription = null)
                ProfileSavePhase.FAILED -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
            }
            Text(
                when (current) {
                    ProfileSavePhase.IDLE -> "Save"
                    ProfileSavePhase.SAVING -> "Saving"
                    ProfileSavePhase.SUCCESS -> "Saved"
                    ProfileSavePhase.FAILED -> "Retry"
                },
            )
        }
    }
}

@Composable
private fun StatusPill(text: String, tone: StatusTone) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val background = when (tone) {
        StatusTone.SUCCESS -> if (dark) Color(0xFF234D34) else Color(0xFFD5F5DE)
        StatusTone.WARNING -> if (dark) Color(0xFF574515) else Color(0xFFFFE9A8)
        StatusTone.ERROR -> if (dark) Color(0xFF5A2928) else Color(0xFFFFDAD6)
        StatusTone.INFO -> if (dark) Color(0xFF24476A) else Color(0xFFD3E4FF)
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when (tone) {
        StatusTone.SUCCESS -> if (dark) Color(0xFFB7F3C8) else Color(0xFF145C2B)
        StatusTone.WARNING -> if (dark) Color(0xFFFFE08A) else Color(0xFF664E00)
        StatusTone.ERROR -> if (dark) Color(0xFFFFB4AB) else Color(0xFF8C1D18)
        StatusTone.INFO -> if (dark) Color(0xFFA8C8F0) else Color(0xFF174A7E)
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = background,
        contentColor = foreground,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ProfileEditor(
    initial: SmtpProfile,
    initialPassword: String,
    testStatus: SmtpTestStatus,
    save: (SmtpProfile, String, (Boolean, String) -> Unit) -> Unit,
    test: (SmtpProfile, String) -> Unit,
) {
    var provider by rememberSaveable(initial.id) { mutableStateOf(initial.provider) }
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var host by rememberSaveable(initial.id) { mutableStateOf(initial.smtpHost) }
    var port by rememberSaveable(initial.id) { mutableStateOf(initial.smtpPort.toString()) }
    var security by rememberSaveable(initial.id) { mutableStateOf(initial.smtpSecurity) }
    var username by rememberSaveable(initial.id) { mutableStateOf(initial.smtpUsername) }
    var from by rememberSaveable(initial.id) { mutableStateOf(initial.fromAddress) }
    var recipients by rememberSaveable(initial.id) { mutableStateOf(initial.recipientAddresses) }
    var password by rememberSaveable(initial.id) { mutableStateOf(initialPassword) }
    var savePhase by rememberSaveable(initial.id) { mutableStateOf(ProfileSavePhase.IDLE) }
    val phase = if (testStatus.profileId == initial.id) testStatus.phase else SmtpTestPhase.IDLE

    fun draft() = initial.copy(
        name = name,
        provider = provider,
        smtpHost = host,
        smtpPort = port.toIntOrNull() ?: 0,
        smtpSecurity = security,
        smtpUsername = username,
        fromAddress = from,
        recipientAddresses = recipients,
    )

    LazyColumn(
        modifier = Modifier.fillMaxHeight(0.92f).imePadding(),
        contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                if (initial.smtpUsername.isBlank()) "Add SMTP profile" else "Edit SMTP profile",
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        item {
            Text("Provider", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmtpProvider.entries.forEach { option ->
                    FilterChip(
                        selected = provider == option,
                        onClick = {
                            provider = option
                            val defaults = option.defaults()
                            name = defaults.name
                            host = defaults.host
                            port = defaults.port.toString()
                            security = defaults.security
                        },
                        label = { Text(providerLabel(option)) },
                    )
                }
            }
        }
        item { AppTextField(name, { name = it }, "Profile name") }
        item { AppTextField(host, { host = it }, "SMTP host") }
        item {
            AppTextField(
                port,
                { port = it.filter(Char::isDigit) },
                "Port",
                KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        item {
            Text("Connection security", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmtpSecurity.entries.forEach { option ->
                    FilterChip(
                        selected = security == option,
                        onClick = {
                            security = option
                            port = if (option == SmtpSecurity.STARTTLS) "587" else "465"
                        },
                        label = { Text(if (option == SmtpSecurity.STARTTLS) "STARTTLS" else "TLS") },
                    )
                }
            }
        }
        item {
            AppTextField(
                username,
                {
                    username = it
                    if (from.isBlank()) from = it
                },
                "Email address",
                KeyboardOptions(keyboardType = KeyboardType.Email),
            )
        }
        item {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("App password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            AppTextField(
                from,
                { from = it },
                "From address",
                KeyboardOptions(keyboardType = KeyboardType.Email),
            )
        }
        item {
            AppTextField(
                recipients,
                { recipients = it },
                "Recipients (comma separated)",
                KeyboardOptions(keyboardType = KeyboardType.Email),
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        savePhase = ProfileSavePhase.SAVING
                        save(draft(), password) { success, _ ->
                            savePhase = if (success) ProfileSavePhase.SUCCESS else ProfileSavePhase.FAILED
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = phase != SmtpTestPhase.TESTING && savePhase != ProfileSavePhase.SAVING,
                ) { SaveButtonContent(savePhase) }
                Button(
                    onClick = { test(draft(), password) },
                    modifier = Modifier.weight(1f),
                    enabled = phase != SmtpTestPhase.TESTING && savePhase != ProfileSavePhase.SAVING,
                ) { TestButtonContent(phase) }
            }
        }
        if (phase == SmtpTestPhase.FAILED && testStatus.message.isNotBlank()) {
            item { Text(testStatus.message, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = keyboardOptions,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun providerLabel(provider: SmtpProvider): String = when (provider) {
    SmtpProvider.GMAIL -> "Gmail"
    SmtpProvider.OUTLOOK -> "Outlook"
    SmtpProvider.CUSTOM -> "Custom"
}

private fun themeModeLabel(themeMode: ThemeMode): String = when (themeMode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun newProfile(provider: SmtpProvider): SmtpProfile {
    val defaults = provider.defaults()
    return SmtpProfile(
        id = UUID.randomUUID().toString(),
        name = defaults.name,
        provider = provider,
        smtpHost = defaults.host,
        smtpPort = defaults.port,
        smtpSecurity = defaults.security,
        smtpUsername = "",
        fromAddress = "",
        recipientAddresses = "",
    )
}
