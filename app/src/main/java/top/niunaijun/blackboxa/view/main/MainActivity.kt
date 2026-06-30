package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.data.game.GameBootService
import top.niunaijun.blackboxa.data.game.TangoCoreInitializer
import top.niunaijun.blackboxa.data.network.CatalogRepository
import top.niunaijun.blackboxa.data.network.model.GameInfo
import top.niunaijun.blackboxa.databinding.ActivityMainBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.fake.FakeManagerActivity
import top.niunaijun.blackboxa.view.setting.SettingActivity

/**
 * MainActivity — Config-driven game dashboard.
 *
 * Reads games from local catalog.json (cached from remote).
 * Displays a clean RecyclerView grid of game cards.
 * On card click: silent download → install → launch.
 * FAB and manual app add are completely removed.
 */
class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()
    private lateinit var gameCardAdapter: GameCardAdapter
    private lateinit var catalogRepository: CatalogRepository
    private lateinit var gameBootService: GameBootService
    private lateinit var tangoCore: TangoCoreInitializer

    private var gameList = mutableListOf<GameInfo>()

    companion object {
        private const val TAG = "MainActivity"
        private const val STORAGE_PERMISSION_REQUEST_CODE = 1001
        private const val VPN_PERMISSION_REQUEST_CODE = 1002
        private const val GRID_COLUMNS = 3

        fun start(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            try {
                BlackBoxCore.get().onBeforeMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onBeforeMainActivityOnCreate: ${e.message}")
            }

            setContentView(viewBinding.root)
            initToolbar(viewBinding.toolbarLayout.toolbar, R.string.app_name)
            initServices()
            initDashboard()
            initTangoCore()
            checkStoragePermission()
            checkVpnPermission()

            try {
                BlackBoxCore.get().onAfterMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onAfterMainActivityOnCreate: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate: ${e.message}")
            showErrorDialog("Failed to initialize app: ${e.message}")
        }
    }

    /**
     * Initialize backend services: CatalogRepository, GameBootService, TangoCore
     */
    private fun initServices() {
        catalogRepository = CatalogRepository(this)
        gameBootService = GameBootService(this)
        tangoCore = TangoCoreInitializer(this)
    }

    /**
     * Initialize the dashboard RecyclerView with grid layout
     */
    private fun initDashboard() {
        gameCardAdapter = GameCardAdapter { game ->
            onGameCardClicked(game)
        }

        viewBinding.rvGameGrid.apply {
            layoutManager = GridLayoutManager(this@MainActivity, GRID_COLUMNS)
            adapter = gameCardAdapter
            setHasFixedSize(true)
        }

        // Load catalog from local cache or network
        loadCatalog()
    }

    /**
     * Initialize Tango Core binary translation layer at app startup
     */
    private fun initTangoCore() {
        try {
            // Pre-initialize for 32-bit games (most catalog games are 32-bit)
            val result = tangoCore.initialize("32bit")
            Log.i(TAG, "Tango Core init: mode=${result.translationMode}, libs=${result.libsLoaded}")
        } catch (e: Exception) {
            Log.e(TAG, "Tango Core init failed: ${e.message}")
        }
    }

    /**
     * Load game catalog — first from local cache, then refresh from network
     */
    private fun loadCatalog() {
        viewBinding.stateView.showLoading()

        // First, load from local cache for instant display
        val cachedGames = catalogRepository.getCachedGames()
        if (cachedGames.isNotEmpty()) {
            gameList = cachedGames.toMutableList()
            gameCardAdapter.submitList(gameList)
            updateGameCount()
            viewBinding.stateView.showContent()
            Log.d(TAG, "Loaded ${cachedGames.size} games from cache")
        }

        // Then refresh from network in background
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    catalogRepository.fetchCatalog()
                }
                result.onSuccess { catalog ->
                    gameList = catalog.gamesList.toMutableList()
                    gameCardAdapter.submitList(gameList)
                    updateGameCount()
                    viewBinding.stateView.showContent()
                    Log.d(TAG, "Loaded ${gameList.size} games from catalog")
                }
                result.onFailure { error ->
                    Log.w(TAG, "Catalog fetch failed: ${error.message}")
                    if (gameList.isEmpty()) {
                        viewBinding.stateView.showEmpty()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Catalog load error: ${e.message}")
                if (gameList.isEmpty()) {
                    viewBinding.stateView.showEmpty()
                }
            }
        }
    }

    private fun updateGameCount() {
        viewBinding.tvGameCount.text = getString(R.string.game_count, gameList.size)
    }

    /**
     * Handle game card click — silent install + launch pipeline
     */
    private fun onGameCardClicked(game: GameInfo) {
        val currentStatus = gameCardAdapter.getInstallStatus(game.gameId)

        // If already installed, just launch
        if (currentStatus == GameCardAdapter.InstallStatus.INSTALLED) {
            launchGame(game)
            return
        }

        // Start silent install pipeline
        gameCardAdapter.setInstallStatus(game.gameId, GameCardAdapter.InstallStatus.DOWNLOADING)

        val progressDialog = MaterialDialog(this).show {
            title(text = game.title)
            message(text = getString(R.string.downloading_game, game.title))
            cancelable(false)
            cornerRadius(12f)
        }

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    gameBootService.bootGame(game)
                }

                progressDialog.dismiss()

                if (result.success) {
                    gameCardAdapter.setInstallStatus(game.gameId, GameCardAdapter.InstallStatus.INSTALLED)
                    // Auto-launch after successful install
                    launchGame(game)
                } else {
                    gameCardAdapter.setInstallStatus(game.gameId, GameCardAdapter.InstallStatus.FAILED)
                    MaterialDialog(this@MainActivity).show {
                        title(text = getString(R.string.install_failed_title))
                        message(text = result.message)
                        positiveButton(text = getString(R.string.retry)) {
                            onGameCardClicked(game)
                        }
                        negativeButton(text = getString(R.string.cancel))
                    }
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                gameCardAdapter.setInstallStatus(game.gameId, GameCardAdapter.InstallStatus.FAILED)
                Log.e(TAG, "Boot failed for ${game.gameId}: ${e.message}")
                MaterialDialog(this@MainActivity).show {
                    title(text = getString(R.string.install_failed_title))
                    message(text = e.message ?: "Unknown error")
                    positiveButton(text = getString(R.string.ok))
                }
            }
        }
    }

    /**
     * Launch an installed game in the sandbox
     */
    private fun launchGame(game: GameInfo) {
        showLoading()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    BlackBoxCore.get().launchApk(game.gameId, 0)
                }
                hideLoading()
                if (!result) {
                    toast(getString(R.string.launch_failed, game.title))
                }
            } catch (e: Exception) {
                hideLoading()
                Log.e(TAG, "Launch failed: ${e.message}")
                toast(getString(R.string.launch_failed, game.title))
            }
        }
    }

    // ──────────────────────────────────────────────────
    //  Permission handling (storage + VPN)
    // ──────────────────────────────────────────────────

    private fun checkStoragePermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    showStoragePermissionDialog()
                }
            } else {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ),
                        STORAGE_PERMISSION_REQUEST_CODE
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Storage permission error: ${e.message}")
        }
    }

    private fun showStoragePermissionDialog() {
        MaterialDialog(this).show {
            title(text = getString(R.string.storage_permission_title))
            message(text = getString(R.string.storage_permission_message))
            positiveButton(text = getString(R.string.grant_permission)) {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                )
                intent.data = Uri.parse("package:$packageName")
                storagePermissionResult.launch(intent)
            }
            negativeButton(text = getString(R.string.later))
            cancelable(false)
        }
    }

    private val storagePermissionResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Permission result handled — app will work with whatever is granted
        }

    private fun checkVpnPermission() {
        try {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPermissionResult.launch(vpnIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "VPN permission error: ${e.message}")
        }
    }

    private val vpnPermissionResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.d(TAG, "VPN permission granted")
            }
        }

    private fun showErrorDialog(message: String) {
        MaterialDialog(this).show {
            title(text = getString(R.string.error_title))
            message(text = message)
            positiveButton(text = getString(R.string.ok)) { finish() }
        }
    }

    // ──────────────────────────────────────────────────
    //  Menu (Settings, Fake Location, Open Source, Telegram)
    // ──────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.main_git -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ALEX5402/NewBlackbox")))
            }
            R.id.main_setting -> {
                SettingActivity.start(this)
            }
            R.id.main_tg -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/newblackboxa")))
            }
            R.id.fake_location -> {
                val intent = Intent(this, FakeManagerActivity::class.java)
                intent.putExtra("userID", 0)
                startActivity(intent)
            }
        }
        return true
    }

    override fun onResume() {
        super.onResume()
        // Refresh catalog when returning to dashboard
        if (::gameCardAdapter.isInitialized && gameList.isNotEmpty()) {
            loadCatalog()
        }
    }

    /**
     * Stub methods kept for backward compatibility with AppsFragment.
     * These are no longer used in the dashboard UI.
     */
    @Suppress("UNUSED_PARAMETER")
    fun showFloatButton(show: Boolean) {
        // FAB removed — no-op
    }

    fun scanUser() {
        // User switching removed — no-op
    }
}
