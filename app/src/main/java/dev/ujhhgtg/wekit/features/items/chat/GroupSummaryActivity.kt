package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsControllerCompat
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.utils.android.isDarkMode

@Keep
class GroupSummaryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            clearFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                    or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }
            WindowInsetsControllerCompat(this, decorView).apply {
                isAppearanceLightStatusBars = !isDarkMode
                isAppearanceLightNavigationBars = !isDarkMode
            }
        }

        val talker = intent.getStringExtra(EXTRA_TALKER)!!
        setContent {
            WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                ModuleTheme {
                    // 状态栏/导航栏着色为页面背景色：背景视觉上延伸到系统栏，内容从状态栏下方开始
                    val barColor = MaterialTheme.colorScheme.surface.toArgb()
                    SideEffect {
                        window.statusBarColor = barColor
                        window.navigationBarColor = barColor
                    }
                    GroupChatSummary.GroupSummaryDialog(
                        talker = talker,
                        onDismiss = ::finish,
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_TALKER = "wekit_group_summary_talker"

        fun launch(context: Context, talker: String) {
            context.startActivity(
                Intent(context, GroupSummaryActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(EXTRA_TALKER, talker)
                }
            )
        }
    }
}
