package io.twoyi.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import io.twoyi.model.GameItem;

public class GameCacheDatabase extends SQLiteOpenHelper {
    
    private static final String DB_NAME = "game_catalog.db";
    private static final int DB_VERSION = 1;
    
    private static final String TABLE_GAMES = "games";
    private static final String COL_ID = "game_id";
    private static final String COL_TITLE = "title";
    private static final String COL_DOWNLOAD_URL = "download_url";
    private static final String COL_API_LEVEL = "api_level";
    private static final String COL_CONTROL_TYPE = "control_type";
    private static final String COL_ICON_URL = "icon_url";
    private static final String COL_DOWNLOADED = "is_downloaded";
    private static final String COL_LOCAL_PATH = "local_path";
    
    private static GameCacheDatabase instance;
    
    public static synchronized GameCacheDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new GameCacheDatabase(context.getApplicationContext());
        }
        return instance;
    }
    
    private GameCacheDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }
    
    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_GAMES + " (" +
            COL_ID + " TEXT PRIMARY KEY, " +
            COL_TITLE + " TEXT, " +
            COL_DOWNLOAD_URL + " TEXT, " +
            COL_API_LEVEL + " TEXT, " +
            COL_CONTROL_TYPE + " TEXT, " +
            COL_ICON_URL + " TEXT, " +
            COL_DOWNLOADED + " INTEGER DEFAULT 0, " +
            COL_LOCAL_PATH + " TEXT)";
        
        db.execSQL(createTable);
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_GAMES);
        onCreate(db);
    }
    
    public void cacheGameCatalog(GameItem[] games) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        
        try {
            db.delete(TABLE_GAMES, null, null);
            
            if (games != null) {
                for (GameItem game : games) {
                    ContentValues values = new ContentValues();
                    values.put(COL_ID, game.getGame_id());
                    values.put(COL_TITLE, game.getTitle());
                    values.put(COL_DOWNLOAD_URL, game.getDownload_url());
                    values.put(COL_API_LEVEL, game.getApi_level());
                    values.put(COL_CONTROL_TYPE, game.getControl_type());
                    values.put(COL_ICON_URL, game.getIcon_url());
                    values.put(COL_DOWNLOADED, 0);
                    values.put(COL_LOCAL_PATH, "");
                    
                    db.insertWithOnConflict(TABLE_GAMES, null, values, 
                        SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
    
    public List<GameItem> getCachedGames() {
        List<GameItem> games = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        
        Cursor cursor = db.query(TABLE_GAMES, null, null, null, null, null, null);
        
        try {
            while (cursor.moveToNext()) {
                GameItem game = new GameItem(
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_DOWNLOAD_URL)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_API_LEVEL)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTROL_TYPE)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_ICON_URL))
                );
                games.add(game);
            }
        } finally {
            cursor.close();
        }
        
        return games;
    }
    
    public List<GameItem> getDownloadedGames() {
        List<GameItem> games = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        
        Cursor cursor = db.query(TABLE_GAMES, null, 
            COL_DOWNLOADED + " = 1", null, null, null, null);
        
        try {
            while (cursor.moveToNext()) {
                GameItem game = new GameItem(
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_DOWNLOAD_URL)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_API_LEVEL)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTROL_TYPE)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_ICON_URL))
                );
                games.add(game);
            }
        } finally {
            cursor.close();
        }
        
        return games;
    }
    
    public void markGameDownloaded(String gameId, String localPath) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_DOWNLOADED, 1);
        values.put(COL_LOCAL_PATH, localPath);
        
        db.update(TABLE_GAMES, values, COL_ID + " = ?", new String[]{gameId});
    }
    
    public boolean isGameDownloaded(String gameId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_GAMES, new String[]{COL_DOWNLOADED},
            COL_ID + " = ?", new String[]{gameId}, null, null, null);
        
        try {
            if (cursor.moveToFirst()) {
                return cursor.getInt(0) == 1;
            }
        } finally {
            cursor.close();
        }
        
        return false;
    }
    
    public String getGameLocalPath(String gameId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_GAMES, new String[]{COL_LOCAL_PATH},
            COL_ID + " = ?", new String[]{gameId}, null, null, null);
        
        try {
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } finally {
            cursor.close();
        }
        
        return null;
    }
}
