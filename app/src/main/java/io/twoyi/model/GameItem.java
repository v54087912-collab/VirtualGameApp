package io.twoyi.model;

public class GameItem {
    private String game_id;
    private String title;
    private String download_url;
    private String api_level;
    private String control_type;
    private String icon_url;

    public GameItem() {}

    public GameItem(String game_id, String title, String download_url, 
                    String api_level, String control_type, String icon_url) {
        this.game_id = game_id;
        this.title = title;
        this.download_url = download_url;
        this.api_level = api_level;
        this.control_type = control_type;
        this.icon_url = icon_url;
    }

    public String getGame_id() {
        return game_id;
    }

    public void setGame_id(String game_id) {
        this.game_id = game_id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDownload_url() {
        return download_url;
    }

    public void setDownload_url(String download_url) {
        this.download_url = download_url;
    }

    public String getApi_level() {
        return api_level;
    }

    public void setApi_level(String api_level) {
        this.api_level = api_level;
    }

    public String getControl_type() {
        return control_type;
    }

    public void setControl_type(String control_type) {
        this.control_type = control_type;
    }

    public String getIcon_url() {
        return icon_url;
    }

    public void setIcon_url(String icon_url) {
        this.icon_url = icon_url;
    }
}
