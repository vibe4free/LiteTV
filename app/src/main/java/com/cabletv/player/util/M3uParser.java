package com.cabletv.player.util;

import com.cabletv.player.model.Channel;
import com.cabletv.player.model.ChannelGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class M3uParser {
    private static final String DEFAULT_GROUP_NAME = "Live";
    private static final Pattern NAME_PATTERN = Pattern.compile(".*,(.+?)$");
    private static final Pattern GROUP_PATTERN = Pattern.compile("group-title=\"(.*?)\"");
    private static final Pattern TVG_LOGO_PATTERN = Pattern.compile("tvg-logo=\"(.*?)\"");
    private static final Pattern TVG_NAME_PATTERN = Pattern.compile("tvg-name=\"(.*?)\"");
    private static final Pattern TVG_ID_PATTERN = Pattern.compile("tvg-id=\"(.*?)\"");
    private static final Pattern HTTP_USER_AGENT_PATTERN = Pattern.compile("http-user-agent=\"(.*?)\"");

    public static List<ChannelGroup> parse(String content) {
        Map<String, ChannelGroup> groupMap = new LinkedHashMap<>();

        if (content == null || content.trim().isEmpty()) {
            return new ArrayList<>();
        }

        try {
            if (content.startsWith("#EXTM3U")) {
                parseM3u(content, groupMap);
            } else {
                parseTxt(content, groupMap);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return new ArrayList<>(groupMap.values());
    }

    private static void parseM3u(String content, Map<String, ChannelGroup> groupMap) {
        try {
            BufferedReader reader = new BufferedReader(
                    new StringReader(content.replace("\r\n", "\n").replace("\r", "")));
            String line;
            String currentGroupName = DEFAULT_GROUP_NAME;
            Channel pendingChannel = null;

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
                    pendingChannel.userAgent = extractPattern(line, HTTP_USER_AGENT_PATTERN);
                    continue;
                }

                if (line.startsWith("#")) continue;

                if (isValidUrl(line) && pendingChannel != null) {
                    pendingChannel.url = line;
                    ChannelGroup group = groupMap.computeIfAbsent(
                            pendingChannel.group,
                            k -> new ChannelGroup(k));
                    group.addChannel(pendingChannel);
                    pendingChannel = null;
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void parseTxt(String content, Map<String, ChannelGroup> groupMap) {
        try {
            BufferedReader reader = new BufferedReader(
                    new StringReader(content.replace("\r\n", "\n").replace("\r", "")));
            String line;
            String currentGroupName = DEFAULT_GROUP_NAME;

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

                String channelName = parts[0].trim();
                String urls = parts[1].trim();

                for (String urlPart : urls.split("#")) {
                    String url = urlPart.trim();
                    if (isValidUrl(url)) {
                        Channel channel = new Channel(channelName, url);
                        channel.group = currentGroupName;
                        ChannelGroup group = groupMap.computeIfAbsent(
                                currentGroupName,
                                k -> new ChannelGroup(k));
                        group.addChannel(channel);
                        break;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String extractPattern(String line, Pattern pattern) {
        Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private static boolean isValidUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }
}
