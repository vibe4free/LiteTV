package com.cabletv.player.epg;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;

import com.cabletv.player.config.AppConfig;
import com.cabletv.player.model.Channel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Loads programme data for the playlist's channels and answers "what is on now".
 *
 * <p>An XMLTV feed describes every channel in one document, so it is downloaded and parsed
 * exactly once per session (or read from the on-disk cache) and streamed with a pull parser
 * instead of being held as a DOM. DIYP sources are the opposite — one small request per
 * channel — so those are fetched lazily as the user reaches a channel.
 *
 * <p>All network, parsing and cache work runs on a single background thread, which is what
 * keeps duplicate downloads impossible without any locking.
 */
public class EpgManager {
    private static final String TAG = "EpgManager";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_REDIRECTS = 5;
    private static final int STREAM_BUFFER_BYTES = 32 * 1024;
    private static final int MAX_DIYP_BYTES = 2 * 1024 * 1024;
    /** Used when a feed gives no usable stop time for the last programme of a channel. */
    private static final long DEFAULT_PROGRAM_DURATION_MS = 30 * 60 * 1000L;
    /** How long to wait before retrying a failed bulk download, so channel surfing cannot spam it. */
    private static final long BULK_RETRY_DELAY_MS = 60 * 1000L;
    /** Lower bound between cache writes while per-channel DIYP results trickle in. */
    private static final long DIYP_CACHE_SAVE_INTERVAL_MS = 15 * 1000L;

    private final EpgCache mCache;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    /** One thread for every download, parse and cache access: no duplicate work, no locks. */
    private final ExecutorService mExecutor =
            Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "epg"));
    /** Programmes per channel key, sorted by start time. See {@link #channelKey(Channel)}. */
    private final Map<String, List<Program>> mProgramsByChannel = new ConcurrentHashMap<>();
    /** Channel keys with a DIYP request in flight; XMLTV needs no such set (one load covers all). */
    private final Set<String> mPendingDiypKeys = Collections.synchronizedSet(new HashSet<String>());

    /**
     * More than one view shows programme text (the channel list and the info bar), so this is a
     * list: a single slot would silently leave whoever registered first out of date.
     */
    private final List<OnEpgUpdatedListener> mListeners = new CopyOnWriteArrayList<>();
    private volatile List<Channel> mKnownChannels = Collections.emptyList();
    private volatile boolean mBulkLoadDone = false;
    private volatile boolean mBulkLoadQueued = false;
    private volatile long mBulkRetryAfter = 0;
    /** Touched only from the EPG thread. */
    private long mLastCacheSaveAt = 0;

    public interface OnEpgUpdatedListener {
        void onEpgUpdated();
    }

    public enum EpgSourceType {
        DIYP,          // per-channel JSON API (51zmt / DIYP style)
        ZIP,           // ZIP package containing an XMLTV document
        XMLTV          // XMLTV document, optionally gzipped
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
        mCache = new EpgCache(context.getApplicationContext());
    }

    public void addOnEpgUpdatedListener(OnEpgUpdatedListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeOnEpgUpdatedListener(OnEpgUpdatedListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Tells the manager which channels exist and starts loading their programmes.
     * Safe to call again after the playlist changes.
     */
    public void preloadEpgForAllChannels(List<Channel> channels) {
        if (channels == null || channels.isEmpty()) {
            Log.d(TAG, "No channels to load EPG for");
            return;
        }
        List<Channel> previous = mKnownChannels;
        mKnownChannels = Collections.unmodifiableList(new ArrayList<>(channels));
        if (!previous.isEmpty() && !sameChannels(previous, mKnownChannels)) {
            // Programmes are keyed by playlist channel, so a different playlist needs a fresh
            // match: keeping the old cache would leave the new channels blank until it expired.
            Log.d(TAG, "Playlist changed, discarding matched EPG data");
            mProgramsByChannel.clear();
            mBulkLoadDone = false;
            mBulkRetryAfter = 0;
            submit(mCache::clearCache);
        }

        String epgUrl = AppConfig.getEpgUrl();
        if (epgUrl == null || epgUrl.isEmpty()) {
            Log.d(TAG, "No EPG URL configured");
            return;
        }

        if (detectEpgSourceType(epgUrl) == EpgSourceType.DIYP) {
            // One request per channel: fetching 150 of them up front would be far worse than
            // loading each channel when the user actually reaches it.
            Log.d(TAG, "DIYP source: loading cache now, channels on demand");
            submit(this::loadCacheIntoMemory);
            return;
        }
        Log.d(TAG, "Requesting EPG for " + channels.size() + " channels from " + epgUrl);
        ensureBulkLoad(epgUrl);
    }

    /**
     * Makes sure the given channel has programmes, if that is cheap. For an XMLTV source this
     * only ever triggers the one bulk load; it never re-downloads the feed per channel.
     */
    public void loadEpg(Channel channel) {
        if (channel == null) {
            return;
        }
        String key = channelKey(channel);
        if (key == null) {
            return;
        }
        List<Program> existing = mProgramsByChannel.get(key);
        if (existing != null && !existing.isEmpty()) {
            return;
        }
        String epgUrl = AppConfig.getEpgUrl();
        if (epgUrl == null || epgUrl.isEmpty()) {
            return;
        }
        if (detectEpgSourceType(epgUrl) == EpgSourceType.DIYP) {
            loadDiypChannel(channel, epgUrl, key);
        } else {
            ensureBulkLoad(epgUrl);
        }
    }

    /** The EPG URL changed: throw away what we have and reload from the new source. */
    public void onEpgSourceChanged() {
        Log.d(TAG, "EPG source changed, dropping cached programmes");
        mProgramsByChannel.clear();
        mBulkLoadDone = false;
        mBulkRetryAfter = 0;
        submit(mCache::clearCache);
        notifyEpgUpdated();

        String epgUrl = AppConfig.getEpgUrl();
        if (epgUrl != null && !epgUrl.isEmpty()
                && detectEpgSourceType(epgUrl) != EpgSourceType.DIYP
                && !mKnownChannels.isEmpty()) {
            ensureBulkLoad(epgUrl);
        }
    }

    /** Stops all pending EPG work. Call from the owning Activity's onDestroy(). */
    public void shutdown() {
        mListeners.clear();
        mExecutor.shutdownNow();
    }

    // ---------------------------------------------------------------- queries

    public Program getCurrentProgram(Channel channel) {
        List<Program> programs = programsFor(channel);
        long now = System.currentTimeMillis();
        for (Program program : programs) {
            if (now >= program.startTime && now < program.endTime) {
                return program;
            }
            if (program.startTime > now) {
                break; // sorted by start time: nothing later can contain "now"
            }
        }
        return null;
    }

    public Program getNextProgram(Channel channel) {
        long now = System.currentTimeMillis();
        for (Program program : programsFor(channel)) {
            if (program.startTime > now) {
                return program;
            }
        }
        return null;
    }

    public List<Program> getAllPrograms(Channel channel) {
        return programsFor(channel);
    }

    public String getCurrentProgramInfo(Channel channel) {
        Program program = getCurrentProgram(channel);
        return program != null ? program.title : null;
    }

    public String getNextProgramInfo(Channel channel) {
        Program program = getNextProgram(channel);
        return program != null ? program.title : null;
    }

    private List<Program> programsFor(Channel channel) {
        if (channel == null) {
            return Collections.emptyList();
        }
        String key = channelKey(channel);
        if (key == null) {
            return Collections.emptyList();
        }
        List<Program> programs = mProgramsByChannel.get(key);
        return programs != null ? programs : Collections.<Program>emptyList();
    }

    // ------------------------------------------------------------ bulk loading

    private void ensureBulkLoad(final String epgUrl) {
        if (mBulkLoadDone || mBulkLoadQueued) {
            return;
        }
        if (System.currentTimeMillis() < mBulkRetryAfter) {
            Log.d(TAG, "Bulk EPG load failed recently, not retrying yet");
            return;
        }
        mBulkLoadQueued = true;
        submit(() -> {
            boolean loaded = false;
            try {
                loaded = runBulkLoad(epgUrl);
            } catch (Exception e) {
                Log.e(TAG, "EPG load failed: " + e.getMessage(), e);
            } finally {
                mBulkLoadQueued = false;
                mBulkLoadDone = loaded;
                mBulkRetryAfter = loaded ? 0 : System.currentTimeMillis() + BULK_RETRY_DELAY_MS;
                notifyEpgUpdated();
            }
        });
    }

    /** Runs on the EPG thread. Returns true when programmes are in memory. */
    private boolean runBulkLoad(String epgUrl) throws IOException, XmlPullParserException {
        List<Channel> channels = mKnownChannels;
        if (channels.isEmpty()) {
            Log.d(TAG, "No known channels yet, skipping EPG load");
            return false;
        }

        if (mCache.isFresh(AppConfig.getEpgCacheValidityHours()) && loadCacheIntoMemory()) {
            Log.d(TAG, "Using cached EPG, no download needed");
            return true;
        }

        EpgSourceType type = detectEpgSourceType(epgUrl);
        Log.d(TAG, "Downloading EPG (" + type + ") from " + epgUrl);
        Map<String, List<Program>> parsed = downloadAndParseXmltv(epgUrl, type, channels);
        if (parsed.isEmpty()) {
            Log.w(TAG, "EPG contained no programmes for any of our " + channels.size() + " channels");
            return false;
        }

        mProgramsByChannel.putAll(parsed);
        Log.i(TAG, "✓ EPG loaded for " + parsed.size() + "/" + channels.size() + " channels");
        mCache.savePrograms(mProgramsByChannel);
        return true;
    }

    /** Reads the cache into memory. Returns true if anything was found. Runs on the EPG thread. */
    private boolean loadCacheIntoMemory() {
        Map<String, List<Program>> cached = mCache.loadPrograms();
        if (cached == null || cached.isEmpty()) {
            Log.d(TAG, "No usable EPG cache");
            return false;
        }
        for (Map.Entry<String, List<Program>> entry : cached.entrySet()) {
            List<Program> programs = entry.getValue();
            if (programs != null && !programs.isEmpty()) {
                mProgramsByChannel.put(entry.getKey(), Collections.unmodifiableList(programs));
            }
        }
        Log.d(TAG, "✓ EPG cache loaded for " + cached.size() + " channels");
        notifyEpgUpdated();
        return true;
    }

    private Map<String, List<Program>> downloadAndParseXmltv(
            String epgUrl, EpgSourceType type, List<Channel> channels)
            throws IOException, XmlPullParserException {
        Map<String, List<String>> lookup = buildChannelLookup(channels);
        HttpURLConnection connection = openConnection(epgUrl);
        InputStream stream = null;
        try {
            stream = new BufferedInputStream(connection.getInputStream(), STREAM_BUFFER_BYTES);
            stream = type == EpgSourceType.ZIP ? openXmlFromZip(stream) : maybeGunzip(stream);
            Map<String, List<Program>> parsed = parseXmltv(stream, lookup);
            finalizePrograms(parsed);
            return parsed;
        } finally {
            closeQuietly(stream);
            connection.disconnect();
        }
    }

    /**
     * Streams the feed with a pull parser, keeping only the programmes of channels we have.
     * The previous implementation built a DOM of the whole document (~20 000 elements for a
     * 3 MB feed) and then re-walked it once per channel.
     *
     * <p>XMLTV lists every {@code <channel>} before the first {@code <programme>}, so one
     * forward pass is enough to resolve ids and collect programmes.
     */
    private Map<String, List<Program>> parseXmltv(InputStream stream, Map<String, List<String>> lookup)
            throws XmlPullParserException, IOException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        // The pull parser never resolves external entities or fetches an external DTD, so the
        // XXE/SSRF hardening the DOM parser needed does not apply here at all — which matters,
        // because this document arrives over cleartext HTTP from a third party.
        // Relaxed mode is best-effort: public feeds do contain stray "&" and HTML entities, and
        // one of those should not throw away the other 20 000 programmes.
        setFeatureQuietly(parser, "http://xmlpull.org/v1/doc/features.html#relaxed", true);
        // Encoding comes from the XML declaration itself.
        parser.setInput(stream, null);

        Map<String, List<String>> xmltvIdToKeys = new HashMap<>();
        Map<String, List<Program>> programsByChannel = new HashMap<>();
        int channelCount = 0;
        int programmeCount = 0;

        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = parser.getName();
                if ("channel".equals(name)) {
                    channelCount++;
                    readChannelElement(parser, lookup, xmltvIdToKeys);
                } else if ("programme".equals(name)) {
                    programmeCount++;
                    readProgrammeElement(parser, xmltvIdToKeys, programsByChannel);
                }
            }
            event = parser.next();
        }

        Log.d(TAG, "Parsed " + channelCount + " channels / " + programmeCount + " programmes; "
                + xmltvIdToKeys.size() + " feed channels matched our playlist");
        return programsByChannel;
    }

    private static void setFeatureQuietly(XmlPullParser parser, String feature, boolean value) {
        try {
            parser.setFeature(feature, value);
        } catch (Exception e) {
            Log.d(TAG, "XML feature not supported by this parser: " + feature);
        }
    }

    /** Parser is on a {@code <channel>} start tag; leaves it on the matching end tag. */
    private void readChannelElement(XmlPullParser parser, Map<String, List<String>> lookup,
                                    Map<String, List<String>> xmltvIdToKeys)
            throws XmlPullParserException, IOException {
        String id = parser.getAttributeValue(null, "id");
        List<String> keys = matchChannelKeys(lookup, id);
        List<String> displayNames = new ArrayList<>(2);

        int depth = 1;
        while (depth > 0) {
            int event = parser.next();
            if (event == XmlPullParser.END_DOCUMENT) {
                break;
            }
            if (event == XmlPullParser.START_TAG) {
                if ("display-name".equals(parser.getName())) {
                    displayNames.add(readElementText(parser));
                } else {
                    depth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }

        if (keys == null) {
            for (String displayName : displayNames) {
                keys = matchChannelKeys(lookup, displayName);
                if (keys != null) {
                    break;
                }
            }
        }
        if (keys != null && id != null && !xmltvIdToKeys.containsKey(id)) {
            xmltvIdToKeys.put(id, keys);
        }
    }

    /** Parser is on a {@code <programme>} start tag; leaves it on the matching end tag. */
    private void readProgrammeElement(XmlPullParser parser, Map<String, List<String>> xmltvIdToKeys,
                                      Map<String, List<Program>> programsByChannel)
            throws XmlPullParserException, IOException {
        String xmltvId = parser.getAttributeValue(null, "channel");
        List<String> keys = xmltvId == null ? null : xmltvIdToKeys.get(xmltvId);
        String start = parser.getAttributeValue(null, "start");
        String stop = parser.getAttributeValue(null, "stop");
        String title = null;

        int depth = 1;
        while (depth > 0) {
            int event = parser.next();
            if (event == XmlPullParser.END_DOCUMENT) {
                break;
            }
            if (event == XmlPullParser.START_TAG) {
                // Everything except the title of a channel we care about is skipped without
                // materialising its text; most of the document is <desc> and <category>.
                if (keys != null && title == null && "title".equals(parser.getName())) {
                    title = readElementText(parser);
                } else {
                    depth++;
                }
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            }
        }

        if (keys == null || title == null || title.isEmpty()) {
            return;
        }
        long startTime = parseXmltvTime(start);
        if (startTime <= 0) {
            return;
        }
        // One feed channel can serve several playlist entries ("四川卫视" and "四川卫视4K"),
        // which share the programme instance.
        Program program = new Program(title, startTime, parseXmltvTime(stop));
        for (String key : keys) {
            List<Program> programs = programsByChannel.get(key);
            if (programs == null) {
                programs = new ArrayList<>();
                programsByChannel.put(key, programs);
            }
            programs.add(program);
        }
    }

    /**
     * Parser is on a start tag; returns its text content and leaves the parser on the
     * matching end tag. Tolerates unexpected child elements, unlike {@code nextText()}.
     */
    private static String readElementText(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        StringBuilder text = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int event = parser.next();
            if (event == XmlPullParser.END_DOCUMENT) {
                break;
            }
            if (event == XmlPullParser.START_TAG) {
                depth++;
            } else if (event == XmlPullParser.END_TAG) {
                depth--;
            } else if (event == XmlPullParser.TEXT && depth == 1) {
                text.append(parser.getText());
            }
        }
        return text.toString().trim();
    }

    // -------------------------------------------------------- channel matching

    /**
     * Builds normalised name/id → channel key index for the playlist. Matching is exact on
     * the normalised form: the old "display name contains channel name" test happily bound
     * CCTV1 to "1905环球经典" and then showed its programmes as CCTV1's.
     */
    private static Map<String, List<String>> buildChannelLookup(List<Channel> channels) {
        Map<String, List<String>> lookup = new HashMap<>();
        for (Channel channel : channels) {
            if (channel == null) {
                continue;
            }
            String key = channelKey(channel);
            if (key == null) {
                continue;
            }
            addLookupToken(lookup, channel.tvgId, key);
            addLookupToken(lookup, channel.name, key);
        }
        return lookup;
    }

    private static void addLookupToken(Map<String, List<String>> lookup, String token, String key) {
        String normalized = normalize(token);
        if (normalized.isEmpty()) {
            return;
        }
        addLookupEntry(lookup, normalized, key);
        String stripped = stripQualitySuffix(normalized);
        if (!stripped.equals(normalized)) {
            addLookupEntry(lookup, stripped, key);
        }
    }

    private static void addLookupEntry(Map<String, List<String>> lookup, String token, String key) {
        List<String> keys = lookup.get(token);
        if (keys == null) {
            keys = new ArrayList<>(1);
            lookup.put(token, keys);
        } else if (keys.contains(key)) {
            return;
        }
        keys.add(key);
    }

    private static List<String> matchChannelKeys(Map<String, List<String>> lookup, String token) {
        String normalized = normalize(token);
        if (normalized.isEmpty()) {
            return null;
        }
        List<String> keys = lookup.get(normalized);
        if (keys != null) {
            return keys;
        }
        // "四川卫视4K" in the playlist and "四川卫视" in the feed are the same channel.
        return lookup.get(stripQualitySuffix(normalized));
    }

    private static boolean sameChannels(List<Channel> first, List<Channel> second) {
        if (first.size() != second.size()) {
            return false;
        }
        Set<String> keys = new HashSet<>();
        for (Channel channel : first) {
            if (channel != null) {
                keys.add(channelKey(channel));
            }
        }
        for (Channel channel : second) {
            if (channel != null && !keys.contains(channelKey(channel))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Programmes are stored per playlist channel, keyed by its normalised name. The tvg-id
     * cannot be the key: playlists reuse ids across entries (90 distinct ids for 151 channels
     * in the feed this was tested against), which silently merged unrelated programmes.
     */
    private static String channelKey(Channel channel) {
        String key = normalize(channel.name);
        if (key.isEmpty()) {
            key = normalize(channel.tvgId);
        }
        return key.isEmpty() ? null : key;
    }

    /** Lowercase, with the separators that feeds and playlists disagree about removed. */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '-' || c == '_' || c == '.' || c == '·') {
                continue;
            }
            normalized.append(Character.toLowerCase(c));
        }
        return normalized.toString();
    }

    private static final String[] QUALITY_SUFFIXES =
            {"4k", "8k", "uhd", "fhd", "hd", "超清", "高清", "标清", "蓝光"};

    private static String stripQualitySuffix(String normalized) {
        for (String suffix : QUALITY_SUFFIXES) {
            if (normalized.length() > suffix.length() && normalized.endsWith(suffix)) {
                return normalized.substring(0, normalized.length() - suffix.length());
            }
        }
        return normalized;
    }

    // ------------------------------------------------------------------- DIYP

    private void loadDiypChannel(final Channel channel, final String baseUrl, final String key) {
        if (!mPendingDiypKeys.add(key)) {
            return;
        }
        submit(() -> {
            try {
                List<Program> programs = fetchDiypPrograms(channel, baseUrl);
                if (programs.isEmpty()) {
                    Log.d(TAG, "DIYP returned no programmes for " + channel.name);
                    return;
                }
                mProgramsByChannel.put(key, Collections.unmodifiableList(programs));
                Log.d(TAG, "✓ DIYP EPG: " + programs.size() + " programmes for " + channel.name);
                // Rewriting the whole cache after every single channel would mean one full write
                // per channel while the user browses the list.
                long now = System.currentTimeMillis();
                if (now - mLastCacheSaveAt > DIYP_CACHE_SAVE_INTERVAL_MS) {
                    mLastCacheSaveAt = now;
                    mCache.savePrograms(mProgramsByChannel);
                }
                notifyEpgUpdated();
            } catch (Exception e) {
                Log.w(TAG, "DIYP EPG failed for " + channel.name + ": " + e.getMessage());
            } finally {
                mPendingDiypKeys.remove(key);
            }
        });
    }

    private List<Program> fetchDiypPrograms(Channel channel, String baseUrl) throws IOException {
        String name = diypChannelToken(channel);
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        // Channel names are Chinese far more often than not, so the substitution must be encoded.
        String url = baseUrl
                .replace("{name}", URLEncoder.encode(name, "UTF-8"))
                .replace("{date}", today);

        HttpURLConnection connection = openConnection(url);
        InputStream stream = null;
        try {
            stream = connection.getInputStream();
            String json = readAsString(stream, MAX_DIYP_BYTES);
            List<Program> programs = parseDiypPrograms(json);
            Collections.sort(programs, PROGRAM_ORDER);
            fixMissingEndTimes(programs);
            return programs;
        } finally {
            closeQuietly(stream);
            connection.disconnect();
        }
    }

    /**
     * A DIYP endpoint looks up channels by name. Playlists routinely put a local stream number
     * in tvg-id (the one this was tested against numbers its 151 channels 1..n), and asking for
     * "ch=56" returns nothing, so a tvg-id is only preferred when it reads like a name.
     */
    private static String diypChannelToken(Channel channel) {
        String tvgId = channel.tvgId;
        if (tvgId != null && !tvgId.isEmpty() && !isAllDigits(tvgId)) {
            return tvgId;
        }
        return channel.name;
    }

    private static boolean isAllDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * DIYP items carry clock times ("start":"20:00") plus the day they belong to. The old
     * parser stored 0 for every start and stop, which made "now playing" guesswork.
     */
    private List<Program> parseDiypPrograms(String json) {
        List<Program> programs = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        JsonArray items;
        long dayStart;

        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            dayStart = startOfDay(optString(object, "date"));
            JsonElement data = object.get("epg_data");
            if (data == null || !data.isJsonArray()) {
                return programs;
            }
            items = data.getAsJsonArray();
        } else if (root.isJsonArray()) {
            dayStart = startOfDay(null);
            items = root.getAsJsonArray();
        } else {
            return programs;
        }

        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).isJsonObject()) {
                continue;
            }
            JsonObject item = items.get(i).getAsJsonObject();
            String title = firstNonEmpty(item, "节目名", "title", "name", "programName");
            if (title == null) {
                continue;
            }
            long start = parseDiypTime(firstNonEmpty(item, "start", "开始时间", "st"), dayStart);
            long end = parseDiypTime(firstNonEmpty(item, "end", "结束时间", "et"), dayStart);
            if (start <= 0) {
                continue;
            }
            if (end > 0 && end <= start) {
                end += 24 * 60 * 60 * 1000L; // programme runs past midnight
            }
            programs.add(new Program(title, start, end));
        }
        return programs;
    }

    /** Accepts "HH:mm", "HH:mm:ss" and "yyyy-MM-dd HH:mm" style values. */
    private static long parseDiypTime(String value, long dayStart) {
        if (value == null) {
            return 0;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return 0;
        }
        int space = text.indexOf(' ');
        if (text.indexOf('-') > 0 && space > 0) {
            long day = startOfDay(text.substring(0, space));
            return day <= 0 ? 0 : day + millisOfDay(text.substring(space + 1));
        }
        long offset = millisOfDay(text);
        return offset < 0 ? 0 : dayStart + offset;
    }

    private static long millisOfDay(String clock) {
        String[] parts = clock.trim().split(":");
        if (parts.length < 2) {
            return -1;
        }
        try {
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            int second = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
            return (hour * 3600L + minute * 60L + second) * 1000L;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Midnight, device timezone, of the given "yyyy-MM-dd" / "yyyyMMdd" date (today if null). */
    private static long startOfDay(String date) {
        Calendar calendar = Calendar.getInstance();
        String digits = date == null ? "" : date.replace("-", "").replace("/", "").trim();
        if (digits.length() >= 8) {
            try {
                calendar.set(Calendar.YEAR, Integer.parseInt(digits.substring(0, 4)));
                calendar.set(Calendar.MONTH, Integer.parseInt(digits.substring(4, 6)) - 1);
                calendar.set(Calendar.DAY_OF_MONTH, Integer.parseInt(digits.substring(6, 8)));
            } catch (NumberFormatException e) {
                Log.d(TAG, "Unparsable EPG date: " + date);
            }
        }
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static String optString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static String firstNonEmpty(JsonObject object, String... fields) {
        for (String field : fields) {
            String value = optString(object, field);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    // ---------------------------------------------------------- time and order

    private static final Comparator<Program> PROGRAM_ORDER =
            (first, second) -> first.startTime < second.startTime
                    ? -1 : (first.startTime == second.startTime ? 0 : 1);

    private static void finalizePrograms(Map<String, List<Program>> programsByChannel) {
        for (Map.Entry<String, List<Program>> entry : programsByChannel.entrySet()) {
            List<Program> programs = entry.getValue();
            Collections.sort(programs, PROGRAM_ORDER);
            fixMissingEndTimes(programs);
            entry.setValue(Collections.unmodifiableList(programs));
        }
    }

    /** Feeds sometimes omit or mangle the stop time; run such an item up to the next one. */
    private static void fixMissingEndTimes(List<Program> programs) {
        for (int i = 0; i < programs.size(); i++) {
            Program program = programs.get(i);
            if (program.endTime > program.startTime) {
                continue;
            }
            program.endTime = i + 1 < programs.size()
                    ? programs.get(i + 1).startTime
                    : program.startTime + DEFAULT_PROGRAM_DURATION_MS;
        }
    }

    /**
     * Parses an XMLTV timestamp such as {@code 20260726004600 +0800}. The offset is part of
     * the value and must be honoured: ignoring it (as the previous implementation did) shifted
     * every programme by the difference between the feed's timezone and the device's.
     */
    static long parseXmltvTime(String value) {
        if (value == null) {
            return 0;
        }
        String text = value.trim();
        int digits = 0;
        while (digits < text.length() && Character.isDigit(text.charAt(digits))) {
            digits++;
        }
        if (digits < 8) {
            return 0;
        }
        int year = number(text, 0, 4);
        int month = number(text, 4, 6);
        int day = number(text, 6, 8);
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return 0;
        }
        long secondsOfDay = (digits >= 10 ? number(text, 8, 10) : 0) * 3600L
                + (digits >= 12 ? number(text, 10, 12) : 0) * 60L
                + (digits >= 14 ? number(text, 12, 14) : 0);
        long utcSeconds = daysFromEpoch(year, month, day) * 86400L + secondsOfDay;

        Integer offsetSeconds = parseUtcOffset(text.substring(digits).trim());
        if (offsetSeconds != null) {
            return (utcSeconds - offsetSeconds) * 1000L;
        }
        // No offset given: XMLTV says the value is local time, so the device's zone is the
        // best available guess.
        long asIfUtc = utcSeconds * 1000L;
        return asIfUtc - TimeZone.getDefault().getOffset(asIfUtc);
    }

    /** Returns null when no offset is present, so callers can fall back to local time. */
    private static Integer parseUtcOffset(String text) {
        if (text.isEmpty()) {
            return null;
        }
        if (text.equalsIgnoreCase("Z") || text.equalsIgnoreCase("UTC") || text.equalsIgnoreCase("GMT")) {
            return 0;
        }
        char sign = text.charAt(0);
        if (sign != '+' && sign != '-') {
            return null;
        }
        String digits = text.substring(1).replace(":", "");
        if (digits.length() < 2) {
            return null;
        }
        try {
            int hours = Integer.parseInt(digits.substring(0, 2));
            int minutes = digits.length() >= 4 ? Integer.parseInt(digits.substring(2, 4)) : 0;
            int offset = hours * 3600 + minutes * 60;
            return sign == '-' ? -offset : offset;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int number(String text, int from, int to) {
        int value = 0;
        for (int i = from; i < to; i++) {
            value = value * 10 + (text.charAt(i) - '0');
        }
        return value;
    }

    /** Days between 1970-01-01 and the given civil date (proleptic Gregorian). */
    private static long daysFromEpoch(int year, int month, int day) {
        long y = year - (month <= 2 ? 1 : 0);
        long era = (y >= 0 ? y : y - 399) / 400;
        long yearOfEra = y - era * 400;
        long dayOfYear = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        long dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
        return era * 146097 + dayOfEra - 719468;
    }

    // --------------------------------------------------------------- transport

    private EpgSourceType detectEpgSourceType(String url) {
        String lower = url.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        String path = query < 0 ? lower : lower.substring(0, query);
        if (path.endsWith(".zip")) {
            return EpgSourceType.ZIP;
        }
        // A DIYP endpoint has to be templated per channel; without a placeholder the same URL
        // would be fetched once per channel, so treat anything else as XMLTV.
        if (url.contains("{name}") || lower.contains("diyp")) {
            return EpgSourceType.DIYP;
        }
        return EpgSourceType.XMLTV;
    }

    private HttpURLConnection openConnection(String urlString) throws IOException {
        String current = urlString;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URL url = new URL(current);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("Redirect without a location from " + current);
                }
                current = new URL(url, location).toString();
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                String message = "HTTP " + code + " " + connection.getResponseMessage();
                connection.disconnect();
                throw new IOException(message + " from " + current);
            }
            return connection;
        }
        throw new IOException("Too many redirects for " + urlString);
    }

    /** Transparently handles feeds whose body is gzipped (the usual {@code .xml.gz}). */
    private static InputStream maybeGunzip(InputStream stream) throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(stream, 2);
        int first = pushback.read();
        int second = pushback.read();
        if (second != -1) {
            pushback.unread(second);
        }
        if (first != -1) {
            pushback.unread(first);
        }
        if (first == 0x1f && second == 0x8b) {
            return new GZIPInputStream(pushback, STREAM_BUFFER_BYTES);
        }
        return pushback;
    }

    /** Positions the stream on the first XML entry of a ZIP packaged EPG. */
    private static InputStream openXmlFromZip(InputStream stream) throws IOException {
        ZipInputStream zip = new ZipInputStream(stream);
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            String name = entry.getName().toLowerCase(Locale.US);
            if (!entry.isDirectory() && (name.endsWith(".xml") || name.endsWith(".xmltv"))) {
                Log.d(TAG, "Reading EPG from ZIP entry " + entry.getName());
                return zip;
            }
        }
        throw new IOException("ZIP contains no XMLTV document");
    }

    private static String readAsString(InputStream stream, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8 * 1024];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            if (buffer.size() + read > maxBytes) {
                throw new IOException("Response exceeds " + maxBytes + " bytes");
            }
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), "UTF-8");
    }

    // ----------------------------------------------------------------- plumbing

    private void submit(Runnable task) {
        try {
            mExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            Log.d(TAG, "EPG task rejected: the manager is shut down");
        }
    }

    private void notifyEpgUpdated() {
        if (mListeners.isEmpty()) {
            return;
        }
        mMainHandler.post(() -> {
            for (OnEpgUpdatedListener listener : mListeners) {
                listener.onEpgUpdated();
            }
        });
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
