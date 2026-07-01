package io.twoyi.model;

public class CatalogResponse {
    private AppMetadata app_metadata;
    private GameItem[] games_list;

    public CatalogResponse() {}

    public CatalogResponse(AppMetadata app_metadata, GameItem[] games_list) {
        this.app_metadata = app_metadata;
        this.games_list = games_list;
    }

    public AppMetadata getApp_metadata() {
        return app_metadata;
    }

    public void setApp_metadata(AppMetadata app_metadata) {
        this.app_metadata = app_metadata;
    }

    public GameItem[] getGames_list() {
        return games_list;
    }

    public void setGames_list(GameItem[] games_list) {
        this.games_list = games_list;
    }
}
