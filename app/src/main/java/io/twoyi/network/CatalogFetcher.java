package io.twoyi.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.twoyi.model.AppMetadata;
import io.twoyi.model.CatalogResponse;
import io.twoyi.model.GameItem;

public class CatalogFetcher {
    
    private static final String CATALOG_URL = 
        "https://raw.githubusercontent.com/Saini920/Games-Releases-virtual-00282hehe/main/catalog.json";
    
    private static final int TIMEOUT_MS = 15000;
    
    public interface CatalogCallback {
        void onSuccess(CatalogResponse response);
        void onError(String error);
    }
    
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    
    public CatalogFetcher(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) 
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }
    
    public void fetchCatalog(CatalogCallback callback) {
        if (!isNetworkAvailable()) {
            callback.onError("No network connection available");
            return;
        }
        
        executor.execute(() -> {
            try {
                CatalogResponse response = performFetch();
                mainHandler.post(() -> callback.onSuccess(response));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
    
    private CatalogResponse performFetch() throws Exception {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        
        try {
            URL url = new URL(CATALOG_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new Exception("HTTP error: " + responseCode);
            }
            
            reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            
            return parseCatalogJson(response.toString());
            
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    private CatalogResponse parseCatalogJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        
        AppMetadata metadata = null;
        if (root.has("app_metadata")) {
            JSONObject metaObj = root.getJSONObject("app_metadata");
            metadata = new AppMetadata(
                metaObj.optString("latest_app_version", ""),
                metaObj.optString("update_type", "flexible")
            );
        }
        
        GameItem[] games = null;
        if (root.has("games_list")) {
            JSONArray gamesArray = root.getJSONArray("games_list");
            games = new GameItem[gamesArray.length()];
            
            for (int i = 0; i < gamesArray.length(); i++) {
                JSONObject gameObj = gamesArray.getJSONObject(i);
                games[i] = new GameItem(
                    gameObj.optString("game_id", ""),
                    gameObj.optString("title", ""),
                    gameObj.optString("download_url", ""),
                    gameObj.optString("api_level", ""),
                    gameObj.optString("control_type", "touch"),
                    gameObj.optString("icon_url", "")
                );
            }
        }
        
        return new CatalogResponse(metadata, games);
    }
    
    public void shutdown() {
        executor.shutdownNow();
    }
}
