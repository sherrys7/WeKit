package dev.ujhhgtg.wekit.ui.utils

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Build
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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.ui.content.nukex.NukeModuleTheme
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.ui.utils.theme.SettingsUiEngine
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import dev.ujhhgtg.wekit.utils.android.isDarkMode

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
            // 不沉浸：decor 默认 fits system windows，内容从状态栏下方开始；
            // 状态栏/导航栏着色为页面背景色（由 Compose 内容侧回写精确的 surface 色），
            // 视觉上背景延伸到系统栏
            window!!.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window!!.clearFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
            )
            // 首帧前的近似底色，避免回写前一闪而过露出宿主界面；随后被内容侧精确覆盖
            val initialBarColor = if (context.isDarkMode) 0xFF1C1B1F.toInt() else Color.WHITE
            window!!.statusBarColor = initialBarColor
            window!!.navigationBarColor = initialBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window!!.isStatusBarContrastEnforced = false
                window!!.isNavigationBarContrastEnforced = false
            }
            WindowInsetsControllerCompat(window!!, window!!.decorView).apply {
                isAppearanceLightStatusBars = !context.isDarkMode
                isAppearanceLightNavigationBars = !context.isDarkMode
            }
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
