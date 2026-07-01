package io.twoyi.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.twoyi.R
import io.twoyi.db.GameCacheDatabase
import io.twoyi.engine.ContainerEngineManager
import io.twoyi.engine.GameLaunchManager
import io.twoyi.model.GameItem
import io.twoyi.network.CatalogFetcher
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * DashboardActivity - Main game library grid with integrated launch workflow
 *
 * Handles:
 * - Displaying game catalog in grid
 * - Game click → availability check → download/install/launch
 * - Real-time progress dialog during installation
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var gamesGrid: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var loadingState: LinearLayout
    private lateinit var offlineHint: TextView
    private lateinit var refreshBtn: FloatingActionButton

    private lateinit var adapter: GameGridAdapter
    private lateinit var catalogFetcher: CatalogFetcher
    private lateinit var database: GameCacheDatabase
    private lateinit var gameLaunchManager: GameLaunchManager
    private lateinit var engineManager: ContainerEngineManager

    private var progressDialog: DownloadProgressDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        initViews()
        initManagers()
        setupGrid()
        setupRefreshButton()
        observeEngineState()

        loadCatalog()
    }

    private fun initViews() {
        gamesGrid = findViewById(R.id.games_grid)
        emptyState = findViewById(R.id.empty_state)
        loadingState = findViewById(R.id.loading_state)
        offlineHint = findViewById(R.id.offline_hint)
        refreshBtn = findViewById(R.id.refresh_btn)
    }

    private fun initManagers() {
        database = GameCacheDatabase.getInstance(this)
        catalogFetcher = CatalogFetcher(this)
        gameLaunchManager = GameLaunchManager.getInstance(this)
        engineManager = ContainerEngineManager.getInstance(this)
    }

    private fun setupGrid() {
        adapter = GameGridAdapter()

        val layoutManager = GridLayoutManager(this, 3)
        gamesGrid.layoutManager = layoutManager
        gamesGrid.adapter = adapter

        adapter.setOnGameClickListener(object : GameGridAdapter.OnGameClickListener {
            override fun onGameClick(game: GameItem) {
                handleGameClick(game)
            }

            override fun onGameLongClick(game: GameItem) {
                showGameOptions(game)
            }
        })
    }

    private fun setupRefreshButton() {
        refreshBtn.setOnClickListener { loadCatalog() }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Game Click Handler - Core Workflow
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Handle game card click - initiates the complete launch workflow.
     *
     * Flow:
     * 1. Check if game is ready to launch (downloaded + installed)
     * 2. If YES: Quick launch directly
     * 3. If NO: Show progress dialog → Download → Extract → Install → Launch
     */
    private fun handleGameClick(game: GameItem) {
        lifecycleScope.launch {
            val isReady = gameLaunchManager.isGameReadyToLaunch(game.game_id)

            if (isReady) {
                // Instant launch - bypass loading screen
                quickLaunchGame(game)
            } else {
                // Full workflow with progress dialog
                startFullLaunchWorkflow(game)
            }
        }
    }

    /**
     * Quick launch for already-installed games.
     * No loading dialog, direct boot.
     */
    private fun quickLaunchGame(game: GameItem) {
        Toast.makeText(this, "Launching ${game.title}...", Toast.LENGTH_SHORT).show()

        gameLaunchManager.quickLaunch(
            gameId = game.game_id,
            surfaceHandle = 0L, // Will be set by SurfaceView
            onComplete = {
                runOnUiThread {
                    Toast.makeText(this, "Game started!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, "Launch failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    /**
     * Full launch workflow with progress dialog.
     * Shows download/install progress and then launches game.
     */
    private fun startFullLaunchWorkflow(game: GameItem) {
        // Show progress dialog
        progressDialog = DownloadProgressDialog(this).apply {
            setGameName(game.title)
            setOnCancelListener {
                gameLaunchManager.cancelLaunch()
            }
            show()
        }

        // Start launch workflow
        gameLaunchManager.launchGame(
            gameId = game.game_id,
            downloadUrl = game.download_url,
            surfaceHandle = 0L, // Will be set by SurfaceView
            onProgress = { state ->
                runOnUiThread {
                    progressDialog?.updateState(state)
                }
            },
            onComplete = {
                runOnUiThread {
                    progressDialog?.dismiss()
                    Toast.makeText(this, "${game.title} started!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                runOnUiThread {
                    progressDialog?.dismiss()
                    showErrorDialog(game, error)
                }
            }
        )
    }

    /**
     * Show error dialog with retry option
     */
    private fun showErrorDialog(game: GameItem, error: String) {
        AlertDialog.Builder(this)
            .setTitle("Launch Failed")
            .setMessage("Failed to launch ${game.title}:\n$error")
            .setPositiveButton("Retry") { _, _ ->
                startFullLaunchWorkflow(game)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Engine State Observer
    // ─────────────────────────────────────────────────────────────────────

    private fun observeEngineState() {
        lifecycleScope.launch {
            engineManager.engineState.collectLatest { state ->
                // Handle engine state changes if needed
                when (state) {
                    is ContainerEngineManager.EngineState.Error -> {
                        runOnUiThread {
                            progressDialog?.dismiss()
                            Toast.makeText(
                                this@DashboardActivity,
                                "Engine error: ${state.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    else -> { /* Handle other states */ }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Game Options (Long Press)
    // ─────────────────────────────────────────────────────────────────────

    private fun showGameOptions(game: GameItem) {
        val options = if (database.isGameDownloaded(game.game_id)) {
            arrayOf("Launch", "Delete", "Cancel")
        } else {
            arrayOf("Download & Install", "Cancel")
        }

        AlertDialog.Builder(this)
            .setTitle(game.title)
            .setItems(options) { _, which ->
                when {
                    // Game is downloaded
                    database.isGameDownloaded(game.game_id) -> {
                        when (which) {
                            0 -> handleGameClick(game)
                            1 -> confirmDeleteGame(game)
                        }
                    }
                    // Game not downloaded
                    which == 0 -> startFullLaunchWorkflow(game)
                }
            }
            .show()
    }

    private fun confirmDeleteGame(game: GameItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Game")
            .setMessage("Remove ${game.title} and all its data?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    gameLaunchManager.deleteGame(game.game_id)
                    database.markGameDownloaded(game.game_id, "")
                    runOnUiThread {
                        loadCatalog()
                        Toast.makeText(this@DashboardActivity, "Deleted: ${game.title}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Catalog Loading
    // ─────────────────────────────────────────────────────────────────────

    private fun loadCatalog() {
        showLoading()

        if (!catalogFetcher.isNetworkAvailable) {
            loadFromCache()
            return
        }

        catalogFetcher.fetchCatalog(object : CatalogFetcher.CatalogCallback {
            override fun onSuccess(response: io.twoyi.model.CatalogResponse) {
                handleCatalogSuccess(response)
            }

            override fun onError(error: String) {
                handleCatalogError(error)
            }
        })
    }

    private fun handleCatalogSuccess(response: io.twoyi.model.CatalogResponse) {
        val games = response.games_list

        if (games != null && games.isNotEmpty()) {
            database.cacheGameCatalog(games)
            showGames(games.toList())
        } else {
            showEmptyState(false)
        }
    }

    private fun handleCatalogError(error: String) {
        Toast.makeText(this, "Failed to load catalog: $error", Toast.LENGTH_SHORT).show()
        loadFromCache()
    }

    private fun loadFromCache() {
        val cachedGames = database.downloadedGames

        if (cachedGames.isEmpty()) {
            showEmptyState(true)
        } else {
            showGames(cachedGames)
            offlineHint.visibility = View.VISIBLE
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: UI State Management
    // ─────────────────────────────────────────────────────────────────────

    private fun showLoading() {
        loadingState.visibility = View.VISIBLE
        gamesGrid.visibility = View.GONE
        emptyState.visibility = View.GONE
    }

    private fun showGames(games: List<GameItem>) {
        loadingState.visibility = View.GONE
        emptyState.visibility = View.GONE
        gamesGrid.visibility = View.VISIBLE

        adapter.setGames(games)
    }

    private fun showEmptyState(isOffline: Boolean) {
        loadingState.visibility = View.GONE
        gamesGrid.visibility = View.GONE
        emptyState.visibility = View.VISIBLE

        offlineHint.visibility = if (isOffline) View.VISIBLE else View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────
    // Region: Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onDestroy() {
        progressDialog?.dismiss()
        catalogFetcher.shutdown()
        super.onDestroy()
    }
}
