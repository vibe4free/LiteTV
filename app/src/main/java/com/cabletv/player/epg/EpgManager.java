package com.cabletv.player.epg;

import android.content.Context;
import android.util.Log;

import com.cabletv.player.config.AppConfig;
import com.cabletv.player.model.Channel;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public class EpgManager {
    private static final String TAG = "EpgManager";
    private final Context mContext;
    private final Map<String, List<Program>> mProgramsByChannel = new HashMap<>();
    private final EpgCache mCache;

    public enum EpgSourceType {
        AUTO,           // Auto-detect based on URL
        DIYP,          // 51zmt DIYP JSON API
        ZIP,           // ZIP package with XMLTV
        XMLTV          // Direct XMLTV URL
    }

    public static class Program {
        public String title;
        public long startTime;
        public long endTime;

        public Program(String title, long startTime, long endTime) {
            this.title = title;
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    public EpgManager(Context context) {
        mContext = context.getApplicationContext();
        mCache = new EpgCache(mContext);
    }

    public void loadEpg(Channel channel) {
        Log.d(TAG, "loadEpg called for channel: " + (channel != null ? channel.name : "null"));
        if (channel == null || channel.tvgId == null || channel.tvgId.isEmpty()) {
            Log.d(TAG, "Channel or tvgId is null/empty, returning");
            return;
        }

        String epgUrl = AppConfig.getEpgUrl();
        Log.d(TAG, "EPG URL from config: " + epgUrl);
        if (epgUrl == null || epgUrl.isEmpty()) {
            Log.d(TAG, "EPG URL is empty, returning");
            return;
        }

        // Detect format based on URL
        EpgSourceType type = detectEpgSourceType(epgUrl);
        Log.d(TAG, "Detected EPG source type: " + type + " for URL: " + epgUrl);

        loadEpgByType(channel, epgUrl, type);
    }

    public void loadFromCache() {
        Log.d(TAG, "Loading EPG from cache file...");
        Map<String, List<Program>> cached = mCache.loadPrograms();
        if (cached != null && !cached.isEmpty()) {
            mProgramsByChannel.putAll(cached);
            Log.d(TAG, "✓ Loaded " + cached.size() + " channels from EPG cache");
        } else {
            Log.d(TAG, "No valid EPG cache found");
        }
    }

    public void preloadEpgForAllChannels(java.util.List<Channel> channels) {
        if (channels == null || channels.isEmpty()) {
            Log.d(TAG, "No channels to preload EPG for");
            return;
        }

        Log.d(TAG, "Starting EPG preload for " + channels.size() + " channels");
        String epgUrl = AppConfig.getEpgUrl();
        if (epgUrl == null || epgUrl.isEmpty()) {
            Log.d(TAG, "EPG URL is empty, cannot preload");
            return;
        }

        EpgSourceType type = detectEpgSourceType(epgUrl);
        if (type == EpgSourceType.XMLTV) {
            // For XMLTV, we can load all channels at once
            loadAllXmltvEpg(channels, epgUrl);
        } else {
            // For other formats, load each channel individually
            for (Channel channel : channels) {
                if (channel != null && channel.tvgId != null && !channel.tvgId.isEmpty()) {
                    loadEpgByType(channel, epgUrl, type);
                }
            }
        }

        Log.d(TAG, "EPG preload initiated at " + System.currentTimeMillis());
    }

    private void loadAllXmltvEpg(java.util.List<Channel> channels, String xmltvUrl) {
        new Thread(() -> {
            try {
                byte[] data = fetchUrlAsBytes(xmltvUrl);
                if (data == null) {
                    Log.e(TAG, "Failed to fetch XMLTV data");
                    return;
                }

                if (isGzipCompressed(data)) {
                    Log.d(TAG, "Content is gzip compressed, decompressing...");
                    String decompressed = decompressGzip(data);
                    data = decompressed.getBytes("UTF-8");
                }

                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                Document doc = builder.parse(new java.io.ByteArrayInputStream(data));

                Log.d(TAG, "Parsing XMLTV EPG for all channels");
                for (Channel channel : channels) {
                    if (channel != null && channel.tvgId != null && !channel.tvgId.isEmpty()) {
                        parseXmltvEpgFromDocument(channel, doc);
                    }
                }

                // Save EPG to cache after loading all channels
                mCache.savePrograms(mProgramsByChannel);
                AppConfig.setEpgLastUpdateTime(System.currentTimeMillis());
                Log.d(TAG, "✓ EPG cache saved successfully");
            } catch (Exception e) {
                Log.e(TAG, "Error loading all XMLTV EPG: " + e.getMessage(), e);
            }
        }).start();
    }

    private void parseXmltvEpgFromDocument(Channel channel, Document doc) {
        try {
            Log.d(TAG, "parseXmltvEpgFromDocument: Starting for " + channel.name + ", tvgId=" + channel.tvgId);

            String channelId = findChannelIdByName(doc, channel);
            if (channelId == null) {
                Log.w(TAG, "Channel not found in XMLTV for: " + channel.name + " (tvgId: " + channel.tvgId + ")");
                return;
            }

            Log.d(TAG, "Found channel ID: " + channelId + " for channel: " + channel.name);

            NodeList programmes = doc.getElementsByTagName("programme");
            long currentTime = System.currentTimeMillis();

            List<Program> programList = new ArrayList<>();
            int matchedCount = 0;
            for (int i = 0; i < programmes.getLength(); i++) {
                Element prog = (Element) programmes.item(i);
                String progChannel = prog.getAttribute("channel");

                if (!progChannel.equals(channelId)) {
                    continue;
                }

                matchedCount++;

                try {
                    long startTime = parseXmltvTime(prog.getAttribute("start"));
                    long stopTime = parseXmltvTime(prog.getAttribute("stop"));

                    String title = "";
                    NodeList titleNodes = prog.getElementsByTagName("title");
                    if (titleNodes.getLength() > 0) {
                        title = titleNodes.item(0).getTextContent();
                    }

                    if (title != null && !title.isEmpty()) {
                        Program program = new Program(title, startTime, stopTime);
                        programList.add(program);
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Error parsing programme " + i + ": " + e.getMessage());
                }
            }

            if (!programList.isEmpty()) {
                mProgramsByChannel.put(channel.tvgId, programList);
                Log.d(TAG, "Stored " + programList.size() + " programs for " + channel.name);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing XMLTV EPG from document: " + e.getMessage(), e);
        }
    }

    private EpgSourceType detectEpgSourceType(String url) {
        if (url.contains("diyp")) {
            return EpgSourceType.DIYP;
        } else if (url.contains("zip")) {
            return EpgSourceType.ZIP;
        } else if (url.contains("xml") || url.endsWith(".gz") || url.endsWith(".xml")) {
            return EpgSourceType.XMLTV;
        }
        // Default to DIYP for 51zmt API style
        return EpgSourceType.DIYP;
    }

    private void loadEpgByType(Channel channel, String epgUrl, EpgSourceType type) {
        new Thread(() -> {
            try {
                switch (type) {
                    case DIYP:
                        loadDiypEpg(channel, epgUrl);
                        break;
                    case ZIP:
                        loadZipEpg(channel, epgUrl);
                        break;
                    case XMLTV:
                        loadXmltvEpg(channel, epgUrl);
                        break;
                    default:
                        loadDiypEpg(channel, epgUrl);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading EPG for " + channel.name, e);
            }
        }).start();
    }

    private void loadDiypEpg(Channel channel, String baseUrl) {
        try {
            String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            String url = baseUrl.replace("{name}", channel.tvgId).replace("{date}", date);

            String content = fetchUrl(url);
            if (content != null && !content.isEmpty()) {
                Log.d(TAG, "DIYP EPG response length: " + content.length());
                parseJsonEpg(channel, content);
                Log.d(TAG, "DIYP EPG loaded for channel: " + channel.name);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error loading DIYP EPG for " + channel.name, e);
        }
    }

    private void loadZipEpg(Channel channel, String baseUrl) {
        try {
            Log.d(TAG, "ZIP EPG format support prepared (requires additional implementation)");
            // TODO: Implement ZIP package download and extraction
            // Format: Download zip from URL, extract XMLTV files, parse by channel
        } catch (Exception e) {
            Log.w(TAG, "Error loading ZIP EPG for " + channel.name, e);
        }
    }

    private void loadXmltvEpg(Channel channel, String xmltvUrl) {
        try {
            Log.d(TAG, "loadXmltvEpg: Starting for channel " + channel.name + " from " + xmltvUrl);
            byte[] content = fetchUrlAsBytes(xmltvUrl);
            if (content != null && content.length > 0) {
                Log.d(TAG, "XMLTV EPG response length: " + content.length + " bytes");
                String xmlString;

                // Check if content is gzip compressed
                if (isGzipCompressed(content)) {
                    Log.d(TAG, "Content is gzip compressed, decompressing...");
                    xmlString = decompressGzip(content);
                    Log.d(TAG, "Decompressed to: " + xmlString.length() + " characters");
                } else {
                    Log.d(TAG, "Content is not compressed, parsing as UTF-8");
                    xmlString = new String(content, "UTF-8");
                }

                Log.d(TAG, "Starting XMLTV XML parsing...");
                parseXmltvEpg(channel, xmlString);
                Log.d(TAG, "XMLTV EPG loaded for channel: " + channel.name);
            } else {
                Log.w(TAG, "XMLTV EPG response is empty for channel: " + channel.name);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading XMLTV EPG for " + channel.name + ": " + e.getMessage(), e);
        }
    }

    private void parseJsonEpg(Channel channel, String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            Log.d(TAG, "Parsing EPG JSON for " + channel.name);

            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();
                if (obj.has("epg_data") && obj.get("epg_data").isJsonArray()) {
                    JsonArray programs = obj.getAsJsonArray("epg_data");
                    parseArray(channel, programs);
                }
            } else if (element.isJsonArray()) {
                JsonArray programs = element.getAsJsonArray();
                parseArray(channel, programs);
            }
        } catch (Exception e) {
            Log.d(TAG, "Failed to parse EPG JSON: " + e.getMessage());
        }
    }

    private void parseArray(Channel channel, JsonArray programs) {
        List<Program> programList = new ArrayList<>();
        for (int i = 0; i < programs.size(); i++) {
            try {
                JsonObject prog = programs.get(i).getAsJsonObject();
                String title = null;

                // Try different field names for program title
                if (prog.has("节目名")) {
                    title = prog.get("节目名").getAsString();
                } else if (prog.has("name")) {
                    title = prog.get("name").getAsString();
                } else if (prog.has("title")) {
                    title = prog.get("title").getAsString();
                }

                if (title != null && !title.isEmpty()) {
                    Program program = new Program(title, 0, 0);
                    programList.add(program);
                }
            } catch (Exception e) {
                Log.d(TAG, "Error parsing program " + i + ": " + e.getMessage());
            }
        }

        if (!programList.isEmpty()) {
            mProgramsByChannel.put(channel.tvgId, programList);
            Log.d(TAG, "Stored " + programList.size() + " programs for " + channel.name);
        }
    }

    public Program getCurrentProgram(Channel channel) {
        if (channel == null) {
            return null;
        }
        List<Program> programs = mProgramsByChannel.get(channel.tvgId);
        if (programs == null || programs.isEmpty()) {
            return null;
        }
        return programs.get(0);
    }

    public Program getNextProgram(Channel channel) {
        if (channel == null) {
            return null;
        }
        List<Program> programs = mProgramsByChannel.get(channel.tvgId);
        if (programs == null || programs.size() < 2) {
            return null;
        }
        return programs.get(1);
    }

    public List<Program> getAllPrograms(Channel channel) {
        if (channel == null) {
            return new ArrayList<>();
        }
        List<Program> programs = mProgramsByChannel.get(channel.tvgId);
        return programs != null ? programs : new ArrayList<>();
    }

    public String getCurrentProgramInfo(Channel channel) {
        Program program = getCurrentProgram(channel);
        if (program != null) {
            return program.title;
        }
        return null;
    }

    public String getNextProgramInfo(Channel channel) {
        Program program = getNextProgram(channel);
        if (program != null) {
            return program.title;
        }
        return null;
    }

    public Program getCurrentProgramWithTime(Channel channel) {
        if (channel == null) {
            return null;
        }
        List<Program> programs = mProgramsByChannel.get(channel.tvgId);
        if (programs == null || programs.isEmpty()) {
            return null;
        }

        long currentTime = System.currentTimeMillis();
        for (Program program : programs) {
            if (currentTime >= program.startTime && currentTime < program.endTime) {
                return program;
            }
        }

        return programs.get(0);
    }

    public String getSupportedFormats() {
        return "DIYP (51zmt JSON API), ZIP (package with XMLTV), XMLTV (direct XML, gzip supported)";
    }

    private byte[] fetchUrlAsBytes(String urlString) {
        return fetchUrlAsBytesWithRedirect(urlString, 0);
    }

    private byte[] fetchUrlAsBytesWithRedirect(String urlString, int redirectCount) {
        if (redirectCount > 5) {
            Log.e(TAG, "Too many redirects!");
            return null;
        }

        try {
            Log.d(TAG, "fetchUrlAsBytes: Starting download from " + urlString + " (redirect #" + redirectCount + ")");
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);

            Log.d(TAG, "Connection opened, sending request...");
            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Response code: " + responseCode);

            if (responseCode >= 300 && responseCode < 400) {
                String redirectLocation = connection.getHeaderField("Location");
                Log.d(TAG, "HTTP redirect " + responseCode + " to: " + redirectLocation);
                if (redirectLocation != null && !redirectLocation.isEmpty()) {
                    return fetchUrlAsBytesWithRedirect(redirectLocation, redirectCount + 1);
                }
                return null;
            } else if (responseCode == HttpURLConnection.HTTP_OK) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                int read;
                byte[] data = new byte[16384];
                int totalRead = 0;

                java.io.InputStream is = connection.getInputStream();
                while ((read = is.read(data)) != -1) {
                    buffer.write(data, 0, read);
                    totalRead += read;
                    if (totalRead % (100 * 1024) == 0) {
                        Log.d(TAG, "Read " + (totalRead / 1024) + " KB so far...");
                    }
                }
                is.close();

                byte[] result = buffer.toByteArray();
                Log.d(TAG, "✓ Successfully fetched " + result.length + " bytes");
                return result;
            } else {
                Log.e(TAG, "HTTP error: " + responseCode + " " + connection.getResponseMessage());
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching URL: " + urlString + " - " + e.getMessage(), e);
        }
        return null;
    }

    private boolean isGzipCompressed(byte[] data) {
        if (data == null || data.length < 2) {
            return false;
        }
        return (data[0] == (byte) 0x1f) && (data[1] == (byte) 0x8b);
    }

    private String decompressGzip(byte[] compressed) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPInputStream gis = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed));
        byte[] buffer = new byte[1024];
        int len;
        while ((len = gis.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
        gis.close();
        return out.toString("UTF-8");
    }

    private void parseXmltvEpg(Channel channel, String xmlString) {
        try {
            Log.d(TAG, "parseXmltvEpg: Starting for " + channel.name + ", tvgId=" + channel.tvgId);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new java.io.ByteArrayInputStream(xmlString.getBytes("UTF-8")));

            Log.d(TAG, "XML document parsed successfully");

            // Count total channels and programmes for debugging
            NodeList allChannels = doc.getElementsByTagName("channel");
            Log.d(TAG, "Total channels in XMLTV: " + allChannels.getLength());

            // Find the channel ID to match
            String channelId = findChannelIdByName(doc, channel);
            if (channelId == null) {
                Log.w(TAG, "Channel not found in XMLTV for: " + channel.name + " (tvgId: " + channel.tvgId + ")");
                return;
            }

            Log.d(TAG, "Found channel ID " + channelId + " for channel: " + channel.name);

            // Get current time
            long currentTime = System.currentTimeMillis();
            Log.d(TAG, "Current time: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(currentTime)));

            // Find matching programme for current time
            NodeList programmes = doc.getElementsByTagName("programme");
            Log.d(TAG, "Total programmes in XMLTV: " + programmes.getLength());

            List<Program> programList = new ArrayList<>();
            int matchedCount = 0;
            for (int i = 0; i < programmes.getLength(); i++) {
                Element prog = (Element) programmes.item(i);
                String progChannel = prog.getAttribute("channel");

                if (!progChannel.equals(channelId)) {
                    continue;
                }

                matchedCount++;

                try {
                    long startTime = parseXmltvTime(prog.getAttribute("start"));
                    long stopTime = parseXmltvTime(prog.getAttribute("stop"));

                    String title = "";
                    NodeList titleNodes = prog.getElementsByTagName("title");
                    if (titleNodes.getLength() > 0) {
                        title = titleNodes.item(0).getTextContent();
                    }

                    if (title != null && !title.isEmpty()) {
                        Program program = new Program(title, startTime, stopTime);
                        programList.add(program);
                        if (currentTime >= startTime && currentTime < stopTime) {
                            Log.d(TAG, "✓ XMLTV program matched for " + channel.name + ": " + title);
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Error parsing programme " + i + ": " + e.getMessage());
                }
            }

            if (!programList.isEmpty()) {
                mProgramsByChannel.put(channel.tvgId, programList);
                Log.d(TAG, "Stored " + programList.size() + " programs for " + channel.name);
            } else {
                Log.w(TAG, "No programmes found for current time in channel: " + channel.name + " (checked " + matchedCount + " programmes for this channel)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing XMLTV EPG: " + e.getMessage(), e);
            e.printStackTrace();
        }
    }

    private String findChannelIdByName(Document doc, Channel channel) {
        NodeList channels = doc.getElementsByTagName("channel");
        String channelName = channel.tvgId;
        if (channelName == null || channelName.isEmpty()) {
            channelName = channel.name;
        }

        String channelNameLower = channelName.toLowerCase();

        for (int i = 0; i < channels.getLength(); i++) {
            Element channelElem = (Element) channels.item(i);
            String id = channelElem.getAttribute("id");

            NodeList displayNames = channelElem.getElementsByTagName("display-name");
            for (int j = 0; j < displayNames.getLength(); j++) {
                String displayName = displayNames.item(j).getTextContent();
                if (displayName != null && displayName.toLowerCase().contains(channelNameLower)) {
                    return id;
                }
            }

            // Also try to match by numeric ID
            if (channelName.matches("\\d+") && id.equals(channelName)) {
                return id;
            }
        }

        return null;
    }

    private long parseXmltvTime(String timeStr) {
        try {
            if (timeStr == null || timeStr.isEmpty()) {
                return 0;
            }

            // Format: "20260726004600 +0800"
            String[] parts = timeStr.trim().split("\\s+");
            if (parts.length < 1) {
                return 0;
            }

            String dateTimeStr = parts[0];
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            Date date = sdf.parse(dateTimeStr);
            return date.getTime();
        } catch (Exception e) {
            Log.d(TAG, "Error parsing XMLTV time: " + timeStr);
            return 0;
        }
    }

    private String fetchUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                return sb.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching URL: " + urlString, e);
        }
        return null;
    }
}
