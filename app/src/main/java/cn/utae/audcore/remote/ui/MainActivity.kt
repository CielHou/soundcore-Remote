package cn.utae.audcore.remote.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cn.utae.audcore.remote.App
import cn.utae.audcore.remote.R
import cn.utae.audcore.remote.data.ConnectionState
import cn.utae.audcore.remote.databinding.ActivityMainBinding
import cn.utae.audcore.remote.service.ConnectionService
import cn.utae.audcore.remote.util.Insets
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * 单 Activity + 左侧抽屉导航。
 *
 * 抽屉是常驻导航：1 连接、2 通道、3 设置，设置里打开「显示运行日志」后出现 4 日志。
 * 因为不再用「连接页跳到另一个 Activity」，返回键、断开重连都能正常回到对应页面。
 */
class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as App
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(app.client, app.preferences)
    }

    private var current = Destination.CONNECT
    private var lastState: ConnectionState? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applyAppBar(binding.appBar)
        Insets.applyContentInsets(binding.fragmentContainer)
        Insets.applyDrawerInsets(binding.navigationView)

        // 不接管 AppCompat 的 ActionBar，直接把菜单挂到 Toolbar 上：
        // 这样 ActionBar 不会把我们自己的导航图标（三条横线）重置掉。
        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item -> onToolbarItem(item) }
        // 不用 ActionBarDrawerToggle 的自动图标，直接给工具栏一个明确的“三条横线”按钮，
        // 确保在任何主题 / 任何系统版本上都能看到并点开抽屉。
        binding.toolbar.setNavigationIcon(R.drawable.ic_menu)
        binding.toolbar.setNavigationOnClickListener {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                binding.drawerLayout.openDrawer(GravityCompat.START)
            }
        }
        binding.navigationView.setNavigationItemSelectedListener(this)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        binding.drawerLayout.isDrawerOpen(GravityCompat.START) ->
                            binding.drawerLayout.closeDrawer(GravityCompat.START)

                        current != Destination.CONNECT -> navigate(Destination.CONNECT)
                        else -> finish()
                    }
                }
            },
        )

        val restored = savedInstanceState?.getString(KEY_DESTINATION)
        current = Destination.entries.firstOrNull { it.name == restored } ?: Destination.CONNECT
        show(current, force = true)

        observeState()
        observeNotices()

        if (app.preferences.token != null && savedInstanceState == null) {
            startConnect()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DESTINATION, current.name)
    }

    override fun onResume() {
        super.onResume()
        refreshDrawerItems()
        refreshToolbarMenuState()
    }

    /** 刷新工具栏菜单项（编辑开关只在通道页出现）。 */
    fun refreshToolbarMenuState() {
        val menu = binding.toolbar.menu
        menu.findItem(R.id.action_edit)?.let { item ->
            val editing = (activeFragment() as? ChannelsFragment)?.isEditMode() == true
            item.isVisible = current == Destination.CHANNELS
            item.title = getString(if (editing) R.string.edit_done else R.string.edit_channels)
        }
    }

    private fun onToolbarItem(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_edit -> {
            (activeFragment() as? ChannelsFragment)?.toggleEditMode()
            refreshToolbarMenuState()
            true
        }

        else -> false
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        val target = Destination.entries.firstOrNull { it.menuId == item.itemId }
        if (target != null) {
            if (target == Destination.LOGS && !app.preferences.showLogs) {
                Snackbar.make(binding.root, R.string.nav_logs_disabled, Snackbar.LENGTH_SHORT).show()
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                return true
            }
            navigate(target)
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    fun refreshDrawerItems() {
        val logs = binding.navigationView.menu.findItem(R.id.nav_logs)
        logs?.isVisible = app.preferences.showLogs
        binding.navigationView.setCheckedItem(current.menuId)
    }

    /** 设置页改动（如显示日志开关）后调用。 */
    fun onSettingsChanged() {
        refreshDrawerItems()
        if (current == Destination.LOGS && !app.preferences.showLogs) {
            navigate(Destination.SETTINGS)
        }
    }

    private fun activeFragment(): Fragment? =
        supportFragmentManager.findFragmentById(R.id.fragmentContainer)

    private fun navigate(destination: Destination) {
        if (current == destination) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            return
        }
        show(destination, force = false)
    }

    private fun show(destination: Destination, force: Boolean) {
        current = destination
        val existing = activeFragment()
        val sameType = when (destination) {
            Destination.CONNECT -> existing is ConnectFragment
            Destination.CHANNELS -> existing is ChannelsFragment
            Destination.SETTINGS -> existing is SettingsFragment
            Destination.LOGS -> existing is LogsFragment
        }
        if (force || !sameType) {
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.fragmentContainer, destination.create())
                .commit()
            binding.toolbar.title = getString(destination.titleRes)
        }
        binding.navigationView.setCheckedItem(destination.menuId)
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        refreshToolbarMenuState()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val previous = lastState
                    lastState = state
                    refreshToolbarMenuState()
                    if (state == ConnectionState.READY && previous != ConnectionState.READY &&
                        current == Destination.CONNECT
                    ) {
                        show(Destination.CHANNELS, force = false)
                    }
                    if (previous != null && (state == ConnectionState.IDLE || state == ConnectionState.FAILED) &&
                        current != Destination.CONNECT
                    ) {
                        show(Destination.CONNECT, force = false)
                    }
                }
            }
        }
    }

    private fun observeNotices() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.notice.collect { notice ->
                    if (!notice.isNullOrBlank()) {
                        Snackbar.make(binding.root, notice, Snackbar.LENGTH_SHORT).show()
                        viewModel.clearNotice()
                    }
                }
            }
        }
    }

    private fun startConnect() {
        maybeRequestNotificationPermission()
        ConnectionService.start(this)
        viewModel.connect()
    }

    private fun maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 抽屉里的四个页面。 */
    enum class Destination(val titleRes: Int, val menuId: Int) {
        CONNECT(R.string.app_name, R.id.nav_connect),
        CHANNELS(R.string.channels_title, R.id.nav_channels),
        SETTINGS(R.string.settings_title, R.id.nav_settings),
        LOGS(R.string.log_title, R.id.nav_logs);

        fun create(): Fragment = when (this) {
            CONNECT -> ConnectFragment()
            CHANNELS -> ChannelsFragment()
            SETTINGS -> SettingsFragment()
            LOGS -> LogsFragment()
        }
    }

    private companion object {
        const val KEY_DESTINATION = "destination"
    }
}
