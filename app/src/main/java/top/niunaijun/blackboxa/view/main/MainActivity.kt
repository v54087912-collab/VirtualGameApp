package top.niunaijun.blackboxa.view.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.data.game.GameDownloadService
import top.niunaijun.blackboxa.data.local.DatabaseHelper
import top.niunaijun.blackboxa.data.network.CatalogRepository
import top.niunaijun.blackboxa.data.network.DownloadManager
import top.niunaijun.blackboxa.data.network.model.GameInfo
import top.niunaijun.blackboxa.databinding.ActivityMainBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.fake.FakeManagerActivity
import top.niunaijun.blackboxa.view.setting.SettingActivity

/**
 * MainActivity — Config-driven game dashboard with zero-manual-step workflow.
 *
 * Complete user flow:
 * 1. App launch → fetch catalog.json (network) → display games in 3-col grid
 * 2. If offline → scan local SQLite DB → show only downloaded/installed games
 * 3. Game card click → check DB: is game_id installed?
 *    → YES: launch directly via BlackBoxCore (no download, no loading screen)
 *    → NO: show real-time progress dialog → download → extract → OBB inject → install → auto-launch
 */
class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()
    private lateinit var gameCardAdapter: GameCardAdapter
    private lateinit var catalogRepository: CatalogRepository
    private lateinit var downloadManager: DownloadManager
    private lateinit var dbHelper: DatabaseHelper

    private var gameList = mutableListOf<GameInfo>()
    private var downloadService: GameDownloadService? = null
    private var progressDialog: AlertDialog? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GameDownloadService.LocalBinder
            downloadService = binder.getService()
            setupServiceCallbacks()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            downloadService = null
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val GRID_COLUMNS = 3

        fun start(context: Context) {
            context.startActivity(Intent(context, MainActivity::class.java))
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
            bindDownloadService()
            checkStoragePermission()
            checkVpnPermission()

            try {
                BlackBoxCore.get().onAfterMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onAfterMainActivityOnCreate: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────
    //  Step 1: Initialize services + dashboard
    // ──────────────────────────────────────────────────

    private fun initServices() {
        catalogRepository = CatalogRepository(this)
        downloadManager = DownloadManager(this)
        dbHelper = DatabaseHelper.getInstance(this)
    }

    private fun initDashboard() {
        gameCardAdapter = GameCardAdapter { game ->
            onGameCardClicked(game)
        }

        viewBinding.rvGameGrid.apply {
            layoutManager = GridLayoutManager(this@MainActivity, GRID_COLUMNS)
            adapter = gameCardAdapter
            setHasFixedSize(true)
        }

        // Load catalog: network first, fallback to local DB
        loadCatalog()
    }

    private fun bindDownloadService() {
        val intent = Intent(this, GameDownloadService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ──────────────────────────────────────────────────
    //  Step 1a: Load catalog (network → local DB fallback)
    // ──────────────────────────────────────────────────

    private fun loadCatalog() {
        viewBinding.stateView.showLoading()

        // Load from local DB for instant display
        lifecycleScope.launch {
            val localGames = withContext(Dispatchers.IO) {
                dbHelper.getAllGames()
            }
            if (localGames.isNotEmpty()) {
                gameList = localGames.map { it.toGameInfo() }.toMutableList()
                gameCardAdapter.submitList(gameList.toList())
                updateGameCount()
                viewBinding.stateView.showContent()
            }

            // Then refresh from network
            try {
                val result = withContext(Dispatchers.IO) {
                    catalogRepository.fetchCatalog()
                }
                result.onSuccess { catalog ->
                    gameList = catalog.gamesList.toMutableList()

                    // Sync to local DB
                    withContext(Dispatchers.IO) {
                        val entities = catalog.gamesList.map { it.toGameEntity() }
                        dbHelper.upsertGames(entities)
                    }

                    gameCardAdapter.submitList(gameList.toList())
                    updateGameCount()
                    viewBinding.stateView.showContent()
                    Log.d(TAG, "Loaded ${gameList.size} games from catalog")
                }
                result.onFailure { error ->
                    Log.w(TAG, "Catalog fetch failed (offline?): ${error.message}")
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

    // ──────────────────────────────────────────────────
    //  Step 2: Game card click — check if installed
    // ──────────────────────────────────────────────────

    private fun onGameCardClicked(game: GameInfo) {
        val isInstalled = dbHelper.isInstalled(game.gameId)

        if (isInstalled) {
            // INSTALLED → launch directly, no loading screen
            launchGame(game)
        } else {
            // NOT INSTALLED → show progress dialog and start pipeline
            showProgressDialog(game)
            GameDownloadService.start(this, game)
        }
    }

    // ──────────────────────────────────────────────────
    //  Step 2a: Launch already-installed game (instant)
    // ──────────────────────────────────────────────────

    private fun launchGame(game: GameInfo) {
        showLoading()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // Use real package name from DB (saved during install), fallback to gameId
                    val entity = dbHelper.getGame(game.gameId)
                    val packageName = entity?.packageName?.takeIf { it.isNotBlank() } ?: game.gameId
                    BlackBoxCore.get().launchApk(packageName, 0)
                }
                hideLoading()
                if (!result) {
                    dbHelper.updateInstallStatus(game.gameId, DatabaseHelper.STATUS_NOT_INSTALLED)
                    toast(getString(R.string.launch_failed, game.title))
                    loadCatalog()
                }
            } catch (e: Exception) {
                hideLoading()
                Log.e(TAG, "Launch failed: ${e.message}")
                dbHelper.updateInstallStatus(game.gameId, DatabaseHelper.STATUS_NOT_INSTALLED)
                toast(getString(R.string.launch_failed, game.title))
                loadCatalog()
            }
        }
    }

    // ──────────────────────────────────────────────────
    //  Step 3: Show real-time progress dialog
    // ──────────────────────────────────────────────────

    private fun showProgressDialog(game: GameInfo) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.custom_loading_dialog, null)

        val ivIcon = dialogView.findViewById<ImageView>(R.id.ivDialogGameIcon)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvDialogStatus)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progressBar)
        val tvPercent = dialogView.findViewById<TextView>(R.id.tvProgressPercent)
        val tvSizeInfo = dialogView.findViewById<TextView>(R.id.tvSizeInfo)
        val btnCancel = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancel)

        tvTitle.text = game.title
        tvStatus.text = getString(R.string.downloading_assets)
        progressBar.progress = 0
        tvPercent.text = "0%"
        tvSizeInfo.text = ""

        // Set game icon placeholder
        ivIcon.setImageBitmap(createGameIcon(game.title))

        progressDialog = AlertDialog.Builder(this, R.style.Theme_BlackBox)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        progressDialog?.show()

        btnCancel.setOnClickListener {
            GameDownloadService.cancel(this)
            progressDialog?.dismiss()
            progressDialog = null
            dbHelper.updateInstallStatus(game.gameId, DatabaseHelper.STATUS_NOT_INSTALLED)
            loadCatalog()
        }
    }

    private fun setupServiceCallbacks() {
        downloadService?.setProgressCallback { progress ->
            runOnUiThread {
                progressDialog?.let { dialog ->
                    val tvStatus = dialog.findViewById<TextView>(R.id.tvDialogStatus)
                    val progressBar = dialog.findViewById<LinearProgressIndicator>(R.id.progressBar)
                    val tvPercent = dialog.findViewById<TextView>(R.id.tvProgressPercent)
                    val tvSizeInfo = dialog.findViewById<TextView>(R.id.tvSizeInfo)

                    tvStatus?.text = getString(R.string.downloading_assets)
                    progressBar?.progress = progress.percentage
                    tvPercent?.text = "${progress.percentage}%"

                    if (progress.totalBytes > 0) {
                        val downloadedMB = progress.bytesDownloaded / (1024.0 * 1024.0)
                        val totalMB = progress.totalBytes / (1024.0 * 1024.0)
                        tvSizeInfo?.text = String.format("%.1f MB / %.1f MB", downloadedMB, totalMB)
                    }
                }
            }
        }

        downloadService?.setStatusCallback { status ->
            runOnUiThread {
                progressDialog?.let { dialog ->
                    val tvStatus = dialog.findViewById<TextView>(R.id.tvDialogStatus)
                    tvStatus?.text = status

                    // Update progress bar for non-download stages
                    val progressBar = dialog.findViewById<LinearProgressIndicator>(R.id.progressBar)
                    val tvPercent = dialog.findViewById<TextView>(R.id.tvProgressPercent)
                    when {
                        status.contains("Extracting", ignoreCase = true) -> {
                            progressBar?.isIndeterminate = true
                            tvPercent?.text = ""
                        }
                        status.contains("Installing", ignoreCase = true) -> {
                            progressBar?.isIndeterminate = true
                            tvPercent?.text = ""
                        }
                        status.contains("Launching", ignoreCase = true) -> {
                            progressBar?.isIndeterminate = true
                            tvPercent?.text = ""
                        }
                    }
                }
            }
        }

        downloadService?.setCompletionCallback { success, message ->
            runOnUiThread {
                progressDialog?.dismiss()
                progressDialog = null

                if (success) {
                    loadCatalog() // Refresh grid to show "Installed" status
                } else {
                    toast("Install failed: $message")
                    loadCatalog()
                }
            }
        }
    }

    // ──────────────────────────────────────────────────
    //  Game icon placeholder generator
    // ──────────────────────────────────────────────────

    private fun createGameIcon(title: String): Bitmap {
        val colors = intArrayOf(
            0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(),
            0xFFFB8C00.toInt(), 0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(),
            0xFFD81B60.toInt(), 0xFF3949AB.toInt(), 0xFF00897B.toInt()
        )
        val colorIndex = title.hashCode().and(0x7FFFFFFF) % colors.size
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = colors[colorIndex]; isAntiAlias = true }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.color = Color.WHITE
        paint.textSize = 56f
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        val letter = title.first().uppercaseChar().toString()
        val textY = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, size / 2f, textY, paint)
        return bitmap
    }

    // ──────────────────────────────────────────────────
    //  Extension: GameInfo ↔ DatabaseHelper.GameEntity
    // ──────────────────────────────────────────────────

    private fun GameInfo.toGameEntity(): DatabaseHelper.GameEntity {
        val existingStatus = dbHelper.getGame(gameId)?.installStatus
            ?: DatabaseHelper.STATUS_NOT_INSTALLED
        return DatabaseHelper.GameEntity(
            gameId = gameId,
            title = title,
            downloadUrl = downloadUrl,
            sha256 = sha256,
            apiLevel = apiLevel,
            architectureType = architectureType,
            controlType = controlType,
            obbUrl = obbUrl,
            installStatus = existingStatus
        )
    }

    private fun DatabaseHelper.GameEntity.toGameInfo(): GameInfo {
        return GameInfo(
            gameId = gameId,
            title = title,
            downloadUrl = downloadUrl,
            sha256 = sha256,
            apiLevel = apiLevel,
            architectureType = architectureType,
            controlType = controlType,
            obbUrl = obbUrl
        )
    }

    // ──────────────────────────────────────────────────
    //  Permission handling
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
                        1001
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Storage permission error: ${e.message}")
        }
    }

    private fun showStoragePermissionDialog() {
        com.afollestad.materialdialogs.MaterialDialog(this).show {
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
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

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
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    // ──────────────────────────────────────────────────
    //  Menu
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
            R.id.main_setting -> SettingActivity.start(this)
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
        if (::gameCardAdapter.isInitialized) {
            loadCatalog()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) { }
        progressDialog?.dismiss()
        progressDialog = null
    }

    /**
     * Stub methods for backward compatibility with AppsFragment.
     */
    @Suppress("UNUSED_PARAMETER")
    fun showFloatButton(show: Boolean) { }
    fun scanUser() { }
}
