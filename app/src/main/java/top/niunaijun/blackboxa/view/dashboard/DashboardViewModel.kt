package top.niunaijun.blackboxa.view.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import top.niunaijun.blackboxa.data.network.CatalogRepository
import top.niunaijun.blackboxa.data.network.ConnectivityObserver
import top.niunaijun.blackboxa.data.network.DownloadManager
import top.niunaijun.blackboxa.data.network.DownloadProgress
import top.niunaijun.blackboxa.data.network.DownloadResult
import top.niunaijun.blackboxa.data.network.model.Catalog
import top.niunaijun.blackboxa.data.network.model.GameInfo

data class DashboardUiState(
    val isLoading: Boolean = false,
    val isOnline: Boolean = true,
    val catalog: Catalog? = null,
    val games: List<GameInfo> = emptyList(),
    val error: String? = null,
    val downloadProgress: Map<String, DownloadProgress> = emptyMap(),
    val downloadedGames: Set<String> = emptySet()
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val connectivityObserver = ConnectivityObserver(application)
    private val catalogRepository = CatalogRepository(application)
    private val downloadManager = DownloadManager(application)

    private val _uiState = MutableLiveData<DashboardUiState>()
    val uiState: LiveData<DashboardUiState> = _uiState

    private val _downloadProgress = MutableLiveData<DownloadProgress>()
    val downloadProgress: LiveData<DownloadProgress> = _downloadProgress

    private val _connectionStatus = connectivityObserver.status

    init {
        _uiState.value = DashboardUiState()
        observeConnectivity()
        refreshCatalog()
    }

    private fun observeConnectivity() {
        _connectionStatus.observeForever { status ->
            val isOnline = status == top.niunaijun.blackboxa.data.network.ConnectionStatus.NOT_METERED ||
                status == top.niunaijun.blackboxa.data.network.ConnectionStatus.METERED
            val current = _uiState.value ?: DashboardUiState()
            _uiState.value = current.copy(isOnline = isOnline)

            if (isOnline && current.catalog == null) {
                refreshCatalog()
            } else if (!isOnline && current.games.isEmpty()) {
                loadCachedGames()
            }
        }
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            val current = _uiState.value ?: DashboardUiState()
            _uiState.value = current.copy(isLoading = true, error = null)

            val result = catalogRepository.fetchCatalog()
            result.onSuccess { catalog ->
                val downloadedGames = catalog.gamesList
                    .filter { downloadManager.isDownloaded(it.gameId) }
                    .map { it.gameId }
                    .toSet()

                _uiState.value = DashboardUiState(
                    isLoading = false,
                    isOnline = current.isOnline,
                    catalog = catalog,
                    games = catalog.gamesList,
                    downloadProgress = current.downloadProgress,
                    downloadedGames = downloadedGames
                )
            }.onFailure { error ->
                val cachedGames = catalogRepository.getCachedGames()
                _uiState.value = current.copy(
                    isLoading = false,
                    error = error.message,
                    games = cachedGames,
                    downloadedGames = cachedGames
                        .filter { downloadManager.isDownloaded(it.gameId) }
                        .map { it.gameId }
                        .toSet()
                )
            }
        }
    }

    fun downloadGame(gameInfo: GameInfo) {
        viewModelScope.launch {
            _downloadProgress.value = DownloadProgress(
                gameId = gameInfo.gameId,
                bytesDownloaded = 0,
                totalBytes = 0,
                percentage = 0
            )

            val result = downloadManager.download(
                gameId = gameInfo.gameId,
                url = gameInfo.downloadUrl,
                onProgress = { progress ->
                    _downloadProgress.postValue(progress)
                }
            )

            when (result) {
                is DownloadResult.Success -> {
                    val current = _uiState.value ?: DashboardUiState()
                    val newDownloaded = current.downloadedGames + gameInfo.gameId
                    _uiState.value = current.copy(downloadedGames = newDownloaded)
                }
                is DownloadResult.Error -> {
                    val current = _uiState.value ?: DashboardUiState()
                    _uiState.value = current.copy(error = result.message)
                }
                is DownloadResult.Cancelled -> {
                }
            }
        }
    }

    fun deleteDownload(gameInfo: GameInfo) {
        downloadManager.deleteDownload(gameInfo.gameId)
        val current = _uiState.value ?: DashboardUiState()
        _uiState.value = current.copy(
            downloadedGames = current.downloadedGames - gameInfo.gameId
        )
    }

    fun clearError() {
        val current = _uiState.value ?: DashboardUiState()
        _uiState.value = current.copy(error = null)
    }

    private fun loadCachedGames() {
        val cachedGames = catalogRepository.getCachedGames()
        val current = _uiState.value ?: DashboardUiState()
        _uiState.value = current.copy(
            games = cachedGames,
            downloadedGames = cachedGames
                .filter { downloadManager.isDownloaded(it.gameId) }
                .map { it.gameId }
                .toSet()
        )
    }

    fun getDownloadManager(): DownloadManager = downloadManager

    override fun onCleared() {
        super.onCleared()
        connectivityObserver.unregister()
    }
}
