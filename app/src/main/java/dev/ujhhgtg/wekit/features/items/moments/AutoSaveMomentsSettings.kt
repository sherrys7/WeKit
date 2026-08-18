package dev.ujhhgtg.wekit.features.items.moments

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.items.AtomicJsonConfigStore
import dev.ujhhgtg.wekit.features.items.AutomationContactSettingsSelector
import dev.ujhhgtg.wekit.features.items.payment.PaymentNavigationRow
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div

internal object AutoSaveMomentsSettings {

    private const val TAG = "AutoSaveMomentsSettings"
    internal const val CONFIG_VERSION = 1

    private val store by lazy {
        AtomicJsonConfigStore(
            file = KnownPaths.moduleData / "auto_save_moments_settings.json",
            serializer = StoredAutoSaveConfig.serializer(),
            tag = TAG,
            initialValue = { StoredAutoSaveConfig() },
        )
    }

    // ==================== Public API ====================

    fun saveTypes(): MomentSaveTypes = store.get().saveTypes

    fun saveDirectory(): String = store.get().saveDirectory

    fun treeUri(): String = store.get().treeUri

    fun isWhitelisted(owner: String): Boolean = store.get().contacts.containsKey(owner)

    fun modeFor(owner: String): MomentAutomationMode? = store.get().contacts[owner]

    fun hasAllLoadedTargets(): Boolean {
        return store.get().contacts.values.any { it == MomentAutomationMode.ALL_LOADED }
    }

    fun resolveSaveRoot(): Path {
        val configured = saveDirectory().trim()
        if (configured.isNotBlank()) return Path(configured)
        return KnownPaths.downloads / "Moments"
    }

    fun showMainDialog(context: Context, onSettingsChanged: () -> Unit) {
        showComposeDialog(context) {
            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(stringResource(R.string.feature_auto_save_moments_name)) },
                text = {
                    Column {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item(key = "global_settings") {
                                PaymentNavigationRow(
                                    title = stringResource(R.string.moments_auto_save_global_settings),
                                    description = stringResource(R.string.moments_auto_save_global_summary),
                                    onClick = { showGlobalDialog(context, onSettingsChanged) },
                                )
                            }
                            item(key = "contact_settings") {
                                PaymentNavigationRow(
                                    title = stringResource(R.string.moments_auto_save_contact_settings),
                                    description = stringResource(R.string.moments_auto_save_contact_summary),
                                    onClick = { showContactSelector(context, onSettingsChanged) },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } },
            )
        }
    }

    // ==================== Settings UI ====================

    private fun showGlobalDialog(context: Context, onSettingsChanged: () -> Unit) {
        showComposeDialog(context) {
            var editDirectory by remember { mutableStateOf(false) }
            if (editDirectory) {
                showDirectoryDialog(
                    context = context,
                    onDismiss = { editDirectory = false },
                    onChanged = { onSettingsChanged() },
                )
                return@showComposeDialog
            }
            val config = store.get()
            val localizedContext = LocalContext.current
            AlertDialogContent(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                title = { Text(stringResource(R.string.moments_auto_save_global_settings)) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        SegmentedColumn(
                            title = stringResource(R.string.moments_auto_save_content_type),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item(key = "type_text") {
                                ContentTypeRow(
                                    title = stringResource(R.string.moments_auto_save_type_text),
                                    checked = config.saveTypes.text,
                                    enabled = true,
                                    onClick = {
                                        store.update { stored ->
                                            stored.copy(version = CONFIG_VERSION, saveTypes = stored.saveTypes.copy(text = !stored.saveTypes.text))
                                        }
                                        onSettingsChanged()
                                    },
                                )
                            }
                            item(key = "type_images") {
                                ContentTypeRow(
                                    title = stringResource(R.string.moments_auto_save_type_images),
                                    checked = config.saveTypes.images,
                                    enabled = true,
                                    onClick = {
                                        store.update { stored ->
                                            stored.copy(version = CONFIG_VERSION, saveTypes = stored.saveTypes.copy(images = !stored.saveTypes.images))
                                        }
                                        onSettingsChanged()
                                    },
                                )
                            }
                            item(key = "type_live_photos") {
                                ContentTypeRow(
                                    title = stringResource(R.string.moments_auto_save_type_live_photos),
                                    checked = config.saveTypes.livePhotos,
                                    enabled = true,
                                    onClick = {
                                        store.update { stored ->
                                            stored.copy(version = CONFIG_VERSION, saveTypes = stored.saveTypes.copy(livePhotos = !stored.saveTypes.livePhotos))
                                        }
                                        onSettingsChanged()
                                    },
                                )
                            }
                            item(key = "type_videos") {
                                ContentTypeRow(
                                    title = stringResource(R.string.moments_auto_save_type_videos),
                                    checked = config.saveTypes.videos,
                                    enabled = true,
                                    onClick = {
                                        store.update { stored ->
                                            stored.copy(version = CONFIG_VERSION, saveTypes = stored.saveTypes.copy(videos = !stored.saveTypes.videos))
                                        }
                                        onSettingsChanged()
                                    },
                                )
                            }
                        }
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item(key = "directory") {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.moments_auto_save_directory),
                                    description = directorySummary(localizedContext),
                                    onClick = { editDirectory = true },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } },
            )
        }
    }

    @Composable
    private fun ContentTypeRow(
        title: String,
        checked: Boolean,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        BaseWidget(
            iconPlaceholder = false,
            title = title,
            enabled = enabled,
            onClick = onClick,
            trailingContent = {
                Checkbox(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = null,
                )
            },
        )
    }

    @Composable
    private fun directorySummary(context: Context): String {
        val treeUri = store.get().treeUri
        return when {
            treeUri.isNotBlank() -> context.getString(R.string.moments_auto_save_directory_summary_system_folder)
            store.get().saveDirectory.isNotBlank() -> store.get().saveDirectory
            else -> context.getString(R.string.moments_auto_save_directory_summary_default)
        }
    }

    private fun showDirectoryDialog(
        context: Context,
        onDismiss: () -> Unit,
        onChanged: () -> Unit,
    ) {
        showComposeDialog(context) {
            var draft by remember { mutableStateOf(store.get().saveDirectory) }
            var revision by remember { mutableIntStateOf(0) }
            val localizedContext = LocalContext.current
            AlertDialogContent(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                title = { Text(stringResource(R.string.moments_auto_save_directory)) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item(key = "system_folder") {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.moments_auto_save_directory_system_folder),
                                    description = if (store.get().treeUri.isNotBlank()) {
                                        localizedContext.getString(R.string.moments_auto_save_directory_system_folder_picked)
                                    } else {
                                        localizedContext.getString(R.string.moments_auto_save_directory_not_picked)
                                    },
                                    onClick = {
                                        pickSystemDirectory(context) { uri ->
                                            store.update { stored ->
                                                stored.copy(
                                                    version = CONFIG_VERSION,
                                                    treeUri = uri.toString(),
                                                    saveDirectory = "",
                                                )
                                            }
                                            draft = ""
                                            revision++
                                            onChanged()
                                        }
                                    },
                                )
                            }
                            item(key = "path") {
                                BaseSupportingWidget(title = stringResource(R.string.moments_auto_save_directory_path)) {
                                    OutlinedTextField(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                        value = draft,
                                        onValueChange = { draft = it },
                                        placeholder = { Text(stringResource(R.string.moments_auto_save_directory_path_hint)) },
                                        singleLine = true,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val trimmed = draft.trim()
                            if (trimmed.isNotBlank()) {
                                store.update { stored ->
                                    stored.copy(
                                        version = CONFIG_VERSION,
                                        saveDirectory = trimmed,
                                        treeUri = "",
                                    )
                                }
                                showToast(localizedContext.getString(R.string.moments_auto_save_directory_saved))
                                onChanged()
                            }
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    private fun showContactSelector(context: Context, onSettingsChanged: () -> Unit) {
        showComposeDialog(context) {
            var revision by remember { mutableIntStateOf(0) }
            val localizedContext = LocalContext.current
            val contacts = remember { loadContacts() }
            AutomationContactSettingsSelector(
                title = stringResource(R.string.moments_auto_save_contact_settings),
                contacts = contacts,
                selectionKey = revision,
                subtitle = { contact ->
                    val mode = store.get().contacts[contact.wxId]
                    if (mode == null) {
                        localizedContext.getString(R.string.moments_auto_save_contact_not_configured)
                    } else {
                        localizedContext.getString(
                            if (mode == MomentAutomationMode.ALL_LOADED) {
                                R.string.moments_auto_save_mode_all_loaded
                            } else {
                                R.string.moments_auto_save_mode_when_seen
                            }
                        )
                    }
                },
                isConfigured = { contact -> store.get().contacts.containsKey(contact.wxId) },
                onDismiss = onDismiss,
                onOpen = { contact ->
                    showContactModeDialog(
                        context = context,
                        contact = contact,
                        onChanged = {
                            revision++
                            onSettingsChanged()
                        },
                    )
                },
            )
        }
    }

    private fun showContactModeDialog(
        context: Context,
        contact: IWeContact,
        onChanged: () -> Unit,
    ) {
        showComposeDialog(context) {
            val localizedContext = LocalContext.current
            val current = store.get().contacts[contact.wxId]
            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(contact.displayName.ifBlank { contact.wxId }) },
                text = {
                    Column {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item(key = "when_seen") {
                                RadioButtonWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.moments_auto_save_mode_when_seen),
                                    selected = current == MomentAutomationMode.WHEN_SEEN,
                                    onClick = {
                                        store.update { stored ->
                                            stored.copy(version = CONFIG_VERSION, contacts = stored.contacts + (contact.wxId to MomentAutomationMode.WHEN_SEEN))
                                        }
                                        onChanged()
                                    },
                                )
                            }
                            item(key = "all_loaded") {
                                RadioButtonWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.moments_auto_save_mode_all_loaded),
                                    description = stringResource(R.string.moments_auto_save_mode_all_loaded_requires_refresh),
                                    selected = current == MomentAutomationMode.ALL_LOADED,
                                    onClick = {
                                        store.update { stored ->
                                            stored.copy(version = CONFIG_VERSION, contacts = stored.contacts + (contact.wxId to MomentAutomationMode.ALL_LOADED))
                                        }
                                        onChanged()
                                    },
                                )
                            }
                            item(key = "remove") {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.moments_auto_save_remove_contact),
                                    enabled = current != null,
                                    onClick = {
                                        store.update { stored ->
                                            stored.copy(version = CONFIG_VERSION, contacts = stored.contacts - contact.wxId)
                                        }
                                        showToast(localizedContext.getString(R.string.moments_auto_save_contact_removed))
                                        onDismiss()
                                        onChanged()
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }

    private fun loadContacts(): List<IWeContact> {
        return runCatching {
            WeDatabaseApi.getFriends().distinctBy { it.wxId }
        }.getOrDefault(emptyList())
    }

    private fun pickSystemDirectory(context: Context, onPicked: (Uri) -> Unit) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri == null) {
                    finish()
                    return@registerForActivityResult
                }
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.onFailure {
                    WeLogger.w(TAG, "failed to take persistable uri permission", it)
                }
                onPicked(uri)
                finish()
            }
            launcher.launch(null)
        }
    }
}

@Serializable
internal data class MomentSaveTypes(
    val text: Boolean = true,
    val images: Boolean = true,
    val livePhotos: Boolean = true,
    val videos: Boolean = true,
)

@Serializable
private data class StoredAutoSaveConfig(
    val version: Int = AutoSaveMomentsSettings.CONFIG_VERSION,
    val saveTypes: MomentSaveTypes = MomentSaveTypes(),
    val saveDirectory: String = "",
    val treeUri: String = "",
    val contacts: Map<String, MomentAutomationMode> = emptyMap(),
)
