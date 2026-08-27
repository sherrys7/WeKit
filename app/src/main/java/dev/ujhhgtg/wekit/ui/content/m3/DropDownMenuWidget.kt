// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2023-2026 iamr0s, InstallerX Revived contributors
package dev.ujhhgtg.wekit.ui.content.m3

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class DropdownOption<T>(val value: T, val label: String)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ExpressiveOptionDropdown(
    expanded: Boolean,
    value: T,
    options: List<DropdownOption<T>>,
    onDismissRequest: () -> Unit,
    onValueChange: (T) -> Unit,
    maxVisibleItems: Int = 6,
) {
    val scrollState = rememberScrollState()
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShapes(),
            modifier = Modifier
                .heightIn(max = 48.dp * maxVisibleItems)
                .verticalScroll(scrollState),
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    selected = option.value == value,
                    onClick = { onValueChange(option.value) },
                    text = { Text(option.label) },
                    shapes = MenuDefaults.itemShape(index, options.size),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> DropDownMenuWidget(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconPlaceholder: Boolean = false,
    title: String,
    description: String?,
    value: T,
    options: List<DropdownOption<T>>,
    enabled: Boolean = true,
    maxVisibleItems: Int = 6,
    onValueChange: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.value == value }

    BaseWidget(
        modifier = modifier,
        icon = icon,
        iconPlaceholder = iconPlaceholder,
        title = title,
        description = description ?: selected?.label ?: "未选择",
        enabled = enabled,
        onClick = if (enabled) ({ expanded = !expanded }) else null,
        foreContent = {
            Box(Modifier.align(Alignment.CenterStart)) {
                ExpressiveOptionDropdown(
                    expanded = expanded,
                    value = value,
                    options = options,
                    onDismissRequest = { expanded = false },
                    onValueChange = {
                        onValueChange(it)
                        expanded = false
                    },
                    maxVisibleItems = maxVisibleItems,
                )
            }
        },
    )
}
