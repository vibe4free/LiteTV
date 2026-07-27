package com.cabletv.player.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class Channel {
    public String name;
    public String logo;
    public String group;
    public String tvgId;

    /**
     * Stream URLs in playlist order. Everything after the first is a fallback, used when the
     * one before it cannot be played. Playlists routinely list several sources per channel.
     */
    public final List<String> urls = new ArrayList<>();

    /** Request headers the source needs (User-Agent, Referer, ...), or null when it needs none. */
    public Map<String, String> headers;

    public Channel() {
    }

    public Channel(String name, String url) {
        this.name = name;
        addUrl(url);
    }

    public Channel(String name, String url, String logo) {
        this(name, url);
        this.logo = logo;
    }

    /** Ignores blanks and duplicates, so a playlist listing the same source twice costs nothing. */
    public void addUrl(String url) {
        if (url != null && !url.isEmpty() && !urls.contains(url)) {
            urls.add(url);
        }
    }

    /** The URL to play first, or null for a channel with no source at all. */
    public String url() {
        return urls.isEmpty() ? null : urls.get(0);
    }

    public String urlAt(int index) {
        return index >= 0 && index < urls.size() ? urls.get(index) : null;
    }

    public int urlCount() {
        return urls.size();
    }

    public boolean hasUrl(String url) {
        return url != null && urls.contains(url);
    }

    public List<String> getUrls() {
        return Collections.unmodifiableList(urls);
    }

    @Override
    public String toString() {
        return "Channel{" +
                "name='" + name + '\'' +
                ", urls=" + urls +
                ", group='" + group + '\'' +
                ", logo='" + logo + '\'' +
                '}';
    }
}
