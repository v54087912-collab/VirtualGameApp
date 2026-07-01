package io.twoyi.model;

public class AppMetadata {
    private String latest_app_version;
    private String update_type;

    public AppMetadata() {}

    public AppMetadata(String latest_app_version, String update_type) {
        this.latest_app_version = latest_app_version;
        this.update_type = update_type;
    }

    public String getLatest_app_version() {
        return latest_app_version;
    }

    public void setLatest_app_version(String latest_app_version) {
        this.latest_app_version = latest_app_version;
    }

    public String getUpdate_type() {
        return update_type;
    }

    public void setUpdate_type(String update_type) {
        this.update_type = update_type;
    }
}
