package com.cabletv.player.model;

import java.util.Map;

public class Channel {
    public String name;
    public String logo;
    public String url;
    public String group;
    public String tvgId;
    public String userAgent;
    public Map<String, String> headers;

    public Channel() {
    }

    public Channel(String name, String url) {
        this.name = name;
        this.url = url;
    }

    public Channel(String name, String url, String logo) {
        this.name = name;
        this.url = url;
        this.logo = logo;
    }

    @Override
    public String toString() {
        return "Channel{" +
                "name='" + name + '\'' +
                ", url='" + url + '\'' +
                ", group='" + group + '\'' +
                ", logo='" + logo + '\'' +
                '}';
    }
}
