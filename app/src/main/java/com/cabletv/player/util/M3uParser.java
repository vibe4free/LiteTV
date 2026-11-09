package com.cabletv.player.util;

import android.util.Log;

import com.cabletv.player.model.Channel;
import com.cabletv.player.model.ChannelGroup;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    /**
     * Catchup (回看/时移) attributes. Not an official M3U spec, but the de-facto convention
     * shared by TiviMate / IPTV Smarters / FongMi / rtp2httpd. Values are quoted strings, so
     * matching them is safe; the URL template is only ever fed to the hardened HTTP player.
     */
    private static final Pattern CATCHUP_PATTERN = Pattern.compile("catchup=\"(.*?)\"");
    private static final Pattern CATCHUP_SOURCE_PATTERN = Pattern.compile("catchup-source=\"(.*?)\"");
    private static final Pattern CATCHUP_REPLACE_PATTERN = Pattern.compile("catchup-replace=\"(.*?)\"");
    /**
     * EPG URL carried on the #EXTM3U header line. Not an official M3U attribute, but the de-facto
     * convention shared by Kodi ({@code url-tvg}), TiviMate / IPTVnator / iPlayTV / GSE
     * ({@code x-tvg-url}) and others ({@code tvg-url}). The value is a quoted string, so matching
     * it here is safe: no code ever runs on it, and only http(s) URLs pass the filter below.
     */
    private static final Pattern EPG_URL_ATTR_PATTERN = Pattern.compile(
            "(?i)(?:x-tvg-url|tvg-url|url-tvg)\\s*=\\s*[\"']([^\"']+)[\"']");
    /** Cap on how many embedded EPG fallbacks a playlist may ask us to try. */
    private static final int MAX_EPG_URLS = 5;

    public static final String HEADER_USER_AGENT = "User-Agent";
    public static final String HEADER_REFERER = "Referer";

    public static List<ChannelGroup> parse(String content) {
        return parse(content, true);
    }

    /**
     * @param mergeSameNameChannels whether entries that repeat a channel name inside one group are
     *        folded into a single channel whose sources are tried in order. Off means the playlist
     *        is taken literally, one channel per entry — which is what a playlist carrying no
     *        repeated names looks like either way.
     */
    public static List<ChannelGroup> parse(String content, boolean mergeSameNameChannels) {
        Map<String, ChannelGroup> groupMap = new LinkedHashMap<>();

        if (content == null || content.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // Index of what has already been collected, keyed by group + name; null disables merging.
        Map<String, Channel> mergeIndex = mergeSameNameChannels ? new LinkedHashMap<String, Channel>() : null;

        if (content.startsWith("#EXTM3U")) {
            parseM3u(content, groupMap, mergeIndex);
        } else {
            parseTxt(content, groupMap, mergeIndex);
        }

        return new ArrayList<>(groupMap.values());
    }

    /**
     * Adds a channel to its group, folding it into an earlier entry for the same channel when
     * merging is on.
     *
     * <p>Playlists from subscription links routinely repeat a channel once per source (five entries
     * named 央视一套, five URLs). Those are the same channel to a viewer, so they become one channel
     * with five sources — the player already fails over between sources and lets the viewer switch
     * them by hand, so nothing else has to change.
     *
     * <p>Only entries with the same group <em>and</em> the same name are merged: the name is what
     * the guide is matched on, so merging cannot disturb the programme list, and an entry with a
     * different name (央视一套高清) stays a channel of its own with its own sources.
     */
    private static void addOrMergeChannel(Map<String, ChannelGroup> groupMap,
                                          Map<String, Channel> mergeIndex,
                                          String groupName, Channel channel) {
        if (mergeIndex == null) {
            groupFor(groupMap, groupName).addChannel(channel);
            return;
        }
        String key = mergeKey(groupName, channel.name);
        Channel existing = mergeIndex.get(key);
        if (existing == null) {
            mergeIndex.put(key, channel);
            groupFor(groupMap, groupName).addChannel(channel);
            return;
        }
        mergeSources(existing, channel);
    }

    private static String mergeKey(String groupName, String channelName) {
        return (groupName == null ? "" : groupName) + "\n" + (channelName == null ? "" : channelName.trim());
    }

    /**
     * Folds a repeated entry into the channel already listed: its sources are appended (duplicates
     * are dropped by {@link Channel#addUrl}) and anything the first entry left blank — logo, id,
     * request headers, catchup — is filled in from this one, so a channel does not lose catchup
     * just because its first source did not carry the attribute.
     */
    private static void mergeSources(Channel target, Channel duplicate) {
        for (String url : duplicate.urls) {
            target.addUrl(url);
        }
        if (isBlank(target.logo)) target.logo = duplicate.logo;
        if (isBlank(target.tvgId)) target.tvgId = duplicate.tvgId;
        if (isBlank(target.catchupType)) target.catchupType = duplicate.catchupType;
        if (isBlank(target.catchupSource)) target.catchupSource = duplicate.catchupSource;
        if (isBlank(target.catchupReplace)) target.catchupReplace = duplicate.catchupReplace;
        if (target.headers == null || target.headers.isEmpty()) {
            target.headers = duplicate.headers;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    /**
     * Extracts the EPG feed URL(s) a playlist embeds on its {@code #EXTM3U} header line, e.g.
     * {@code #EXTM3U x-tvg-url="http://host/epg.xml.gz"}. Multiple values are tried in order, so
     * space- or pipe-separated lists keep their fallback meaning. Only http(s) URLs are accepted;
     * a value without a scheme is resolved against the playlist URL when that one is http(s), so
     * a relative path cannot point anywhere outside the playlist's own origin.
     */
    public static List<String> parseEpgUrls(String content, String playlistUrl) {
        if (content == null) {
            return Collections.emptyList();
        }
        String header = normalizeNewlines(content).split("\n", 2)[0];
        if (!header.startsWith("#EXTM3U")) {
            return Collections.emptyList();
        }

        List<String> urls = new ArrayList<>();
        Matcher matcher = EPG_URL_ATTR_PATTERN.matcher(header);
        while (matcher.find() && urls.size() < MAX_EPG_URLS) {
            // Playlists separate several guide addresses with spaces, "|" or commas; a comma
            // left on the end of an address makes it useless, so it counts as a separator too.
            for (String raw : matcher.group(1).split("[\\s|,;]+")) {
                String url = resolveEpgUrl(raw.trim(), playlistUrl);
                if (url != null && !urls.contains(url)) {
                    urls.add(url);
                    if (urls.size() >= MAX_EPG_URLS) {
                        break;
                    }
                }
            }
        }
        return urls;
    }

    /**
     * Turns a browser address into the address that actually serves the file.
     *
     * <p>People copy the address they see in the browser, which for a file hosted on a code host
     * is a page showing that file rather than the file itself. Downloading the page yields HTML,
     * the playlist parses to nothing, and the only thing the viewer sees is "could not be
     * parsed". The mapping is mechanical, so it happens here instead of asking the viewer to
     * know it.
     *
     * @return the address to download, or the input unchanged when it is not a page address
     */
    public static String normalizePlaylistUrl(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        java.util.regex.Matcher matcher = WEB_FILE_PAGE.matcher(trimmed);
        if (matcher.matches()) {
            return "https://raw.githubusercontent.com/" + matcher.group(1) + "/"
                    + matcher.group(2) + "/" + matcher.group(3);
        }
        return trimmed;
    }

    /** <code>host/&lt;owner&gt;/&lt;repo&gt;/blob/&lt;ref&gt;/&lt;path&gt;</code>: the page a browser shows for a file. */
    private static final java.util.regex.Pattern WEB_FILE_PAGE =
            java.util.regex.Pattern.compile(
                    "(?i)^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/blob/(.+)$");

    /**
     * True when the body looks like a web page rather than a playlist.
     *
     * <p>Lets the player say what actually went wrong instead of a generic parse failure.
     */
    public static boolean looksLikeWebPage(String content) {
        if (content == null) {
            return false;
        }
        String head = content.length() > 600 ? content.substring(0, 600) : content;
        String lower = head.toLowerCase(java.util.Locale.US).trim();
        return lower.startsWith("<!doctype html") || lower.startsWith("<html");
    }

    /** @return an http(s) EPG URL, or null when the value is unusable. */
    private static String resolveEpgUrl(String value, String playlistUrl) {
        // Trailing punctuation survives splitting in some generated playlists and would break the
        // download silently ("...xml.gz," is not a URL).
        value = value.replaceAll("^[\"'\\s]+|[\"'\\s]+$", "");
        if (value.isEmpty()) {
            return null;
        }
        String lower = value.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return value;
        }
        // A relative path is only meaningful against an http(s) playlist; against a local file it
        // could name anything on the device, so it is dropped there.
        if (playlistUrl == null || playlistUrl.isEmpty()
                || !(playlistUrl.startsWith("http://") || playlistUrl.startsWith("https://"))) {
            return null;
        }
        try {
            URL resolved = new URL(new URL(playlistUrl), value);
            String scheme = resolved.getProtocol() == null ? "" : resolved.getProtocol().toLowerCase(Locale.US);
            return ("http".equals(scheme) || "https".equals(scheme)) ? resolved.toString() : null;
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static void parseM3u(String content, Map<String, ChannelGroup> groupMap,
                                 Map<String, Channel> mergeIndex) {
        BufferedReader reader = new BufferedReader(new StringReader(normalizeNewlines(content)));
        String line;
        String currentGroupName = DEFAULT_GROUP_NAME;
        Channel pendingChannel = null;
        // The #EXTM3U header may carry catchup defaults that per-channel attributes override.
        String defaultCatchupType = "";
        String defaultCatchupSource = "";
        String defaultCatchupReplace = "";
        // VLC options may sit above the #EXTINF line they belong to. They are collected here and
        // applied once the channel exists, instead of being dropped because there was no channel
        // to attach them to yet.
        List<String> leadingVlcOptions = new ArrayList<>();

        try {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#EXTM3U")) {
                    defaultCatchupType = extractPattern(line, CATCHUP_PATTERN);
                    defaultCatchupSource = extractPattern(line, CATCHUP_SOURCE_PATTERN);
                    defaultCatchupReplace = extractPattern(line, CATCHUP_REPLACE_PATTERN);
                    continue;
                }

                if (line.startsWith("#EXTINF")) {
                    // Whatever the previous entry collected is finished now: a playlist may put
                    // several URL lines under one #EXTINF, and every one of them is a source.
                    commitPendingChannel(groupMap, mergeIndex, pendingChannel);
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
                    for (String option : leadingVlcOptions) {
                        applyVlcOption(pendingChannel, option);
                    }
                    leadingVlcOptions.clear();
                    putHeader(pendingChannel, HEADER_USER_AGENT,
                            extractPattern(line, HTTP_USER_AGENT_PATTERN));
                    putHeader(pendingChannel, HEADER_REFERER,
                            extractPattern(line, HTTP_REFERRER_PATTERN));
                    // A channel's own catchup wins; otherwise inherit the playlist default.
                    pendingChannel.catchupType = firstNonEmpty(
                            extractPattern(line, CATCHUP_PATTERN), defaultCatchupType);
                    pendingChannel.catchupSource = firstNonEmpty(
                            extractPattern(line, CATCHUP_SOURCE_PATTERN), defaultCatchupSource);
                    pendingChannel.catchupReplace = firstNonEmpty(
                            extractPattern(line, CATCHUP_REPLACE_PATTERN), defaultCatchupReplace);
                    continue;
                }

                // VLC carries the same information on its own option lines, which some
                // generators emit instead of the #EXTINF attributes.
                if (line.startsWith("#EXTVLCOPT:")) {
                    String option = line.substring("#EXTVLCOPT:".length());
                    if (pendingChannel != null) {
                        applyVlcOption(pendingChannel, option);
                    } else {
                        leadingVlcOptions.add(option);
                    }
                    continue;
                }

                if (line.startsWith("#")) continue;

                if (pendingChannel != null) {
                    String url = takeInlineHeaders(pendingChannel, line);
                    if (isValidUrl(url)) {
                        // Kept collecting until the next #EXTINF: additional URL lines below the
                        // same entry belong to the same channel as fallbacks.
                        pendingChannel.addUrl(url);
                    }
                }
            }
            commitPendingChannel(groupMap, mergeIndex, pendingChannel);
        } catch (Exception e) {
            // A truncated or malformed playlist still yields every channel read so far.
            Log.w(TAG, "Stopped parsing M3U early: " + e);
        }
    }

    /** Files the entry that just ended, if it collected at least one usable source. */
    private static void commitPendingChannel(Map<String, ChannelGroup> groupMap,
                                             Map<String, Channel> mergeIndex, Channel pendingChannel) {
        if (pendingChannel != null && pendingChannel.urlCount() > 0) {
            addOrMergeChannel(groupMap, mergeIndex, pendingChannel.group, pendingChannel);
        }
    }

    private static void parseTxt(String content, Map<String, ChannelGroup> groupMap,
                                 Map<String, Channel> mergeIndex) {
        BufferedReader reader = new BufferedReader(new StringReader(normalizeNewlines(content)));
        String line;
        String currentGroupName = DEFAULT_GROUP_NAME;

        try {
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.endsWith("#genre#")) {
                    // Accept both the traditional "央视频道,#genre#" and the comma-less
                    // "央视频道#genre#" a local file may well carry; neither may leak the
                    // marker into the group name.
                    String name = line.substring(0, line.length() - "#genre#".length()).trim();
                    if (name.endsWith(",")) {
                        name = name.substring(0, name.length() - 1).trim();
                    }
                    currentGroupName = name;
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
                    addOrMergeChannel(groupMap, mergeIndex, currentGroupName, channel);
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

    /** @return the first non-empty value, falling back to the default. */
    private static String firstNonEmpty(String value, String fallback) {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    private static boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }
}
