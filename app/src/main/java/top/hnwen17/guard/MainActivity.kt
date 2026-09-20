package top.hnwen17.guard

import android.content.Intent
import android.os.Bundle
import android.os.StrictMode
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.widget.Toast
import kotlinx.coroutines.launch
import top.hnwen17.guard.databinding.ActivityMainBinding
import top.hnwen17.guard.ui.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val model: GuardViewModel by viewModels()
    private var selectedTab = "home"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = maxOf(bars.bottom, ime.bottom))
            insets
        }
        if (BuildConfig.PREVIEW_DATA && !(application as GuardApplication).previewAcknowledged) {
            AlertDialog.Builder(this).setTitle("轻护 · 原生界面预览")
                .setMessage("这是 Kotlin 原生页面。应用、保护次数、规则版本和防护状态为设计示例，未执行广告拦截。设置页会保留此说明。")
                .setPositiveButton("进入界面") { _, _ -> (application as GuardApplication).previewAcknowledged = true }
                .setCancelable(false).show()
        }
        binding.navHome.setOnClickListener { navigate("home") }
        binding.navApps.setOnClickListener { navigate("apps") }
        binding.navRecords.setOnClickListener { model.filterApp(null); navigate("records") }
        binding.navSubs.setOnClickListener { navigate("subs") }
        binding.navSettings.setOnClickListener { navigate("settings") }
        selectedTab = savedInstanceState?.getString("tab") ?: "home"
        supportFragmentManager.addOnBackStackChangedListener { refreshNavigation() }
        if (savedInstanceState == null) {
            val destination = if (BuildConfig.DEBUG) intent.getStringExtra("screen") ?: "home" else "home"
            if (destination == "detail" && BuildConfig.PREVIEW_DATA) {
                navigate("apps"); supportFragmentManager.executePendingTransactions(); openDetail("sample.shop")
            } else navigate(destination)
        } else refreshNavigation()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.messages.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
            }
        }
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        }
    }
    override fun onResume() {
        super.onResume()
        top.hnwen17.guard.platform.shizuku.ShizukuBridge.reprobe() // QH-P05-08 按需重探
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tab", selectedTab)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.DEBUG) {
            val screen = intent.getStringExtra("screen") ?: return
            if (screen == "detail" && BuildConfig.PREVIEW_DATA) openDetail("sample.shop") else navigate(screen)
        }
    }
    fun navigate(destination: String) {
        if (supportFragmentManager.isStateSaved) return
        val tab = destination.takeIf { it in setOf("home", "apps", "records", "subs", "settings") } ?: "home"
        if (supportFragmentManager.backStackEntryCount > 0)
            supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        val fragment: Fragment = when (tab) {
            "apps" -> AppsFragment()
            "records" -> RecordsFragment()
            "subs" -> SubsFragment()
            "settings" -> SettingsFragment()
            else -> HomeFragment()
        }
        selectedTab = tab
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            if (!model.state.value.settings.reduceMotion) setCustomAnimations(R.anim.page_enter, R.anim.page_exit)
            replace(R.id.content, fragment, tab)
        }
        binding.bottomNav.isVisible = true
        refreshNavigationSelection()
    }
    fun openDetail(appId: String) {
        if (supportFragmentManager.isStateSaved) return
        supportFragmentManager.commit {
            setReorderingAllowed(true)
            if (!model.state.value.settings.reduceMotion) setCustomAnimations(R.anim.page_enter, R.anim.page_exit, R.anim.page_enter, R.anim.page_exit)
            replace(R.id.content, DetailFragment().apply { arguments = Bundle().apply { putString("appId", appId) } }, "detail")
            addToBackStack("detail")
        }
        binding.bottomNav.isVisible = true
        selectedTab = "apps"
        refreshNavigationSelection()
    }
    private fun refreshNavigation() {
        binding.bottomNav.isVisible = true
        refreshNavigationSelection()
    }
    private fun refreshNavigationSelection() {
        binding.navHome.isSelected = selectedTab == "home"
        binding.navApps.isSelected = selectedTab == "apps"
        binding.navRecords.isSelected = selectedTab == "records"
        binding.navSubs.isSelected = selectedTab == "subs"
        binding.navSettings.isSelected = selectedTab == "settings"
    }
}
