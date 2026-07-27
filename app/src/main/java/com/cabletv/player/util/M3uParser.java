package com.cabletv.player.util;

import android.util.Log;

import com.cabletv.player.model.Channel;
import com.cabletv.player.model.ChannelGroup;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class M3uParser {
    private static final String TAG = "M3uParser";
    private static final String DEFAULT_GROUP_NAME = "Live";
    private static final Pattern NAME_PATTERN = Pattern.compile(".*,(.+?)$");
    private static final Pattern GROUP_PATTERN = Pattern.compile("group-title=\"(.*?)\"");
    private static final Pattern TVG_LOGO_PATTERN = Pattern.compile("tvg-logo=\"(.*?)\"");
    private static final Pattern TVG_ID_PATTERN = Pattern.compile("tvg-id=\"(.*?)\"");
    private static final Pattern HTTP_USER_AGENT_PATTERN = Pattern.compile("http-user-agent=\"(.*?)\"");
    private static final Pattern HTTP_REFERRER_PATTERN = Pattern.compile("http-referr?er=\"(.*?)\"");

    public static final String HEADER_USER_AGENT = "User-Agent";
    public static final String HEADER_REFERER = "Referer";

    public static List<ChannelGroup> parse(String content) {
        Map<String, ChannelGroup> groupMap = new LinkedHashMap<>();

        if (content == null || content.trim().isEmpty()) {
            return new ArrayList<>();
        }

        if (content.startsWith("#EXTM3U")) {
            parseM3u(content, groupMap);
        } else {
            parseTxt(content, groupMap);
        }

        return new ArrayList<>(groupMap.values());
    }

    private static void parseM3u(String content, Map<String, ChannelGroup> groupMap) {
        BufferedReader reader = new BufferedReader(new StringReader(normalizeNewlines(content)));
        String line;
        String currentGroupName = DEFAULT_GROUP_NAME;
        Channel pendingChannel = null;

        try {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#EXTINF")) {
                    pendingChannel = new Channel();
                    pendingChannel.name = extractPattern(line, NAME_PATTERN);
                    pendingChannel.group = extractPattern(line, GROUP_PATTERN);
                    if (pendingChannel.group.isEmpty()) {
                        pendingChannel.group = currentGroupName;
                    } else {
                        currentGroupName = pendingChannel.group;
                    }
                    pendingChannel.logo = extractPattern(line, TVG_LOGO_PATTERN);
                    pendingChannel.tvgId = extractPattern(line, TVG_ID_PATTERN);
                    putHeader(pendingChannel, HEADER_USER_AGENT,
                            extractPattern(line, HTTP_USER_AGENT_PATTERN));
                    putHeader(pendingChannel, HEADER_REFERER,
                            extractPattern(line, HTTP_REFERRER_PATTERN));
                    continue;
                }

                // VLC carries the same information on its own option lines, which some
                // generators emit instead of the #EXTINF attributes.
                if (line.startsWith("#EXTVLCOPT:") && pendingChannel != null) {
                    applyVlcOption(pendingChannel, line.substring("#EXTVLCOPT:".length()));
                    continue;
                }

                if (line.startsWith("#")) continue;

                if (pendingChannel != null) {
                    String url = takeInlineHeaders(pendingChannel, line);
                    if (isValidUrl(url)) {
                        pendingChannel.addUrl(url);
                        groupFor(groupMap, pendingChannel.group).addChannel(pendingChannel);
                        pendingChannel = null;
                    }
                }
            }
        } catch (Exception e) {
            // A truncated or malformed playlist still yields every channel read so far.
            Log.w(TAG, "Stopped parsing M3U early: " + e);
        }
    }

    private static void parseTxt(String content, Map<String, ChannelGroup> groupMap) {
        BufferedReader reader = new BufferedReader(new StringReader(normalizeNewlines(content)));
        String line;
        String currentGroupName = DEFAULT_GROUP_NAME;

        try {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.endsWith("#genre#")) {
                    currentGroupName = line.split(",")[0].trim();
                    continue;
                }

                if (line.startsWith("#")) continue;

                String[] parts = line.split(",", 2);
                if (parts.length < 2) continue;

                Channel channel = new Channel();
                channel.name = parts[0].trim();
                channel.group = currentGroupName;
                // "name,url1#url2#url3": every URL is a source for the same channel, so keep
                // them all as fallbacks instead of dropping everything after the first.
                for (String urlPart : parts[1].trim().split("#")) {
                    String url = takeInlineHeaders(channel, urlPart.trim());
                    if (isValidUrl(url)) {
                        channel.addUrl(url);
                    }
                }
                if (channel.urlCount() > 0) {
                    groupFor(groupMap, currentGroupName).addChannel(channel);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Stopped parsing playlist early: " + e);
        }
    }

    private static ChannelGroup groupFor(Map<String, ChannelGroup> groupMap, String name) {
        ChannelGroup group = groupMap.get(name);
        if (group == null) {
            group = new ChannelGroup(name);
            groupMap.put(name, group);
        }
        return group;
    }

    private static void applyVlcOption(Channel channel, String option) {
        int eq = option.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String key = option.substring(0, eq).trim();
        String value = option.substring(eq + 1).trim();
        if ("http-user-agent".equalsIgnoreCase(key)) {
            putHeader(channel, HEADER_USER_AGENT, value);
        } else if ("http-referrer".equalsIgnoreCase(key) || "http-referer".equalsIgnoreCase(key)) {
            putHeader(channel, HEADER_REFERER, value);
        }
    }

    /**
     * Splits the "url|User-Agent=x&Referer=y" form used by many playlists, moving the suffix
     * into the channel's headers. Returns the bare URL.
     */
    private static String takeInlineHeaders(Channel channel, String url) {
        int bar = url.indexOf('|');
        if (bar < 0) {
            return url;
        }
        for (String pair : url.substring(bar + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if ("user-agent".equalsIgnoreCase(key)) {
                putHeader(channel, HEADER_USER_AGENT, value);
            } else if ("referer".equalsIgnoreCase(key) || "referrer".equalsIgnoreCase(key)) {
                putHeader(channel, HEADER_REFERER, value);
            } else if (!key.isEmpty()) {
                putHeader(channel, key, value);
            }
        }
        return url.substring(0, bar);
    }

    private static void putHeader(Channel channel, String name, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (channel.headers == null) {
            channel.headers = new LinkedHashMap<>();
        }
        channel.headers.put(name, value);
    }

    private static String normalizeNewlines(String content) {
        return content.replace("\r\n", "\n").replace("\r", "");
    }

    private static String extractPattern(String line, Pattern pattern) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private static boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }
}
