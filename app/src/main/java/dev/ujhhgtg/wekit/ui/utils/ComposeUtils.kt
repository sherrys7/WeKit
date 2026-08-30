package dev.ujhhgtg.wekit.ui.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.ui.content.nukex.NukeModuleTheme
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.ui.utils.theme.SettingsUiEngine
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings

// useful for showing a compose dialog in non-compose context,
// or when you don't want to manage the state for a dialog inside a composable
//
// note that you should use AlertDialogContent instead of AlertDialog inside 'content' to avoid
// creating multiple windows
fun showComposeDialog(
    context: Context,
    directlyDismissable: Boolean = true,
    fullScreen: Boolean = false,
    content: @Composable ShowComposeDialogScope.() -> Unit
) {
    val context = CommonContextWrapper(context)

    val dialog = ComponentDialog(
        context,
        android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth
    )

    dialog.apply {
        window!!.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            requestFeature(Window.FEATURE_NO_TITLE)
        }

        setCancelable(directlyDismissable)

        val scope = ShowComposeDialogScope(context, this, window!!, ::dismiss)

        setContentView(
            ComposeView(context).apply {
                setContent {
                    WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                        val themedContent: @Composable () -> Unit = {
                            ModuleTheme {
                                Box(
                                    modifier = if (fullScreen) {
                                        Modifier.fillMaxSize()
                                    } else {
                                        Modifier.wrapContentSize()
                                    },
                                    contentAlignment = Alignment.Center
                                ) {
                                    scope.content()
                                }
                            }
                        }
                        if (ThemeSettings.uiEngine == SettingsUiEngine.NUKE) {
                            NukeModuleTheme(content = themedContent)
                        } else {
                            themedContent()
                        }
                    }
                }
            }
        )

        window!!.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        if (fullScreen) {
            window!!.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            // 全屏沉浸：透明状态栏/导航栏，内容延伸至系统栏区域（由 Composable 侧 insets padding 处理避让）
            window!!.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window!!.statusBarColor = Color.TRANSPARENT
            window!!.navigationBarColor = Color.TRANSPARENT
            WindowCompat.setDecorFitsSystemWindows(window!!, false)
        }
        show()
    }
}

class ShowComposeDialogScope(
    val context: Context,
    val dialog: Dialog,
    val window: Window,
    val onDismiss: () -> Unit
)

fun View.setLifecycleOwner(lifecycleOwner: XposedLifecycleOwner) {
    setViewTreeLifecycleOwner(lifecycleOwner)
    setViewTreeViewModelStoreOwner(lifecycleOwner)
    setViewTreeSavedStateRegistryOwner(lifecycleOwner)
}
