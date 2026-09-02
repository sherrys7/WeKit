package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Person_remove
import com.composables.icons.materialsymbols.outlined.Search
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeGroupApi
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 低活跃成员弹窗：按发言数升序展示成员，支持搜索昵称/微信号、勾选后批量移出群聊。
 * @param talker 当前群聊 ID
 * @param members 低活跃成员（含 0 条发言），来自 [loadActivityResult]
 */
@Composable
internal fun LowActivityMembersDialog(
    talker: String,
    members: List<LowActivityMember>,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    val selfWxId = WeApi.selfWxId
    val inactiveCount = members.count { it.count == 0 }
    val scope = rememberCoroutineScope()

    val filtered = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) members
        else members.filter { it.name.lowercase().contains(q) || it.wxid.lowercase().contains(q) }
    }

    AlertDialogContent(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ui_group_low_members_title, members.size),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Icon(
                        MaterialSymbols.Outlined.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.ui_group_low_members_tip, inactiveCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.ui_group_low_members_search_hint)) },
                    leadingIcon = { Icon(MaterialSymbols.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                filtered.forEach { member ->
                    val isSelf = member.wxid == selfWxId
                    val checked = member.wxid in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isSelf) {
                                if (checked) selected.remove(member.wxid) else selected.add(member.wxid)
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (checked) MaterialTheme.colorScheme.primary else Color.Transparent,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.size(20.dp),
                        ) {
                            if (checked) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "✓",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isSelf) {
                                    stringResource(R.string.ui_group_low_members_self, member.name)
                                } else {
                                    member.name
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (member.count == 0) {
                                    stringResource(R.string.ui_group_low_members_never)
                                } else {
                                    stringResource(R.string.ui_group_low_members_msg_count, member.count)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                if (filtered.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ui_group_low_members_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(vertical = 32.dp)
                            .fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            val removedToast = stringResource(R.string.ui_group_low_members_removed, selected.size)
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            WeGroupApi.delMembers(talker, selected.toList())
                        }
                        showToast(removedToast)
                        onDismiss()
                    }
                },
                enabled = selected.isNotEmpty(),
            ) {
                Icon(MaterialSymbols.Outlined.Person_remove, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ui_group_low_members_remove, selected.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}
