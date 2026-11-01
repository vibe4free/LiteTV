package com.cabletv.player.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import com.cabletv.player.config.AppConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP-level tests of the config web server's local-file support: /config/get reports the
 * active file, POST /config/m3u-file-clear removes it and notifies the app, and the
 * cross-site / content-type guards still hold. Uses raw sockets because the test runs under
 * Robolectric, which shadows HttpURLConnection rather than doing real networking.
 */
@RunWith(RobolectricTestRunner.class)
public class ConfigWebServerTest {

    private final AtomicReference<String> mLastFileChange = new AtomicReference<>();
    private int mPort;
    /** Set when the web page saves a display preference. */
    private boolean mDisplayChangeSeen;

    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AppConfig.setM3uUrl("");
        AppConfig.setM3uFilePath("");
        mPort = freePort();
        ConfigWebServer.setConfigChangeListener(new ConfigWebServer.OnConfigChangeListener() {
            @Override public void onM3uUrlChanged(String u) { }
            @Override public void onEpgUrlChanged(String u) { }
            @Override public void onEpgPreferenceChanged(String p) { }
            @Override public void onM3uFileChanged(String path) { mLastFileChange.set(path); }
            @Override public void onDisplaySettingsChanged() { mDisplayChangeSeen = true; }
        });
        ConfigWebServer.startServer(context, mPort);
        mLastFileChange.set(null);
    }

    @After
    public void tearDown() {
        ConfigWebServer.stopServer();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** Sends one HTTP request over a raw socket. Returns {status, body}. */
    private String[] request(String method, String path, String extraHeaders, String body)
            throws IOException {
        Socket socket = new Socket("127.0.0.1", mPort);
        socket.setSoTimeout(10_000);
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: localhost:").append(mPort).append("\r\n");
        head.append("Connection: close\r\n");
        if (extraHeaders != null) {
            head.append(extraHeaders);
        }
        if (body != null) {
            head.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length)
                    .append("\r\n");
        }
        head.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        if (body != null) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        out.flush();

        InputStream in = socket.getInputStream();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            raw.write(buf, 0, n);
        }
        socket.close();

        String all = raw.toString("UTF-8");
        int sep = all.indexOf("\r\n\r\n");
        String headPart = sep >= 0 ? all.substring(0, sep) : all;
        String bodyPart = sep >= 0 ? all.substring(sep + 4) : "";
        Matcher m = Pattern.compile("HTTP/1.1 (\\d+)").matcher(headPart);
        if (!m.find()) {
            fail("No status line in response: " + all);
        }
        return new String[]{m.group(1), bodyPart};
    }

    @Test
    public void upload_storesTheFileActivatesItAndNotifiesTheApp() throws Exception {
        String boundary = "----LiteTVTest";
        String playlist = "#EXTM3U\n#EXTINF:-1,Uploaded One\nhttp://stream/one\n"
                + "#EXTINF:-1,上传频道\nhttp://stream/two\n";
        String res[] = request("POST", "/config/m3u-file-upload",
                "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n",
                multipartBody(boundary, "uploaded.m3u", playlist));
        assertEquals("200", res[0]);
        assertTrue("must report success: " + res[1], res[1].contains("\"success\":true"));

        String stored = AppConfig.getM3uFilePath();
        assertTrue("a path must be recorded, got " + stored, stored != null && stored.endsWith("local.m3u"));
        File file = new File(stored);
        assertTrue("the playlist must exist on disk", file.exists());
        // Non-ASCII content must survive the byte-exact multipart handling.
        String readBack = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8);
        assertEquals("the stored bytes must match the upload exactly", playlist, readBack);
        assertEquals("the app must be told to reload", stored, mLastFileChange.get());
    }

    @Test
    public void upload_rejectsSomethingThatIsNotAPlaylist() throws Exception {
        String boundary = "----LiteTVTest";
        String[] res = request("POST", "/config/m3u-file-upload",
                "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n",
                multipartBody(boundary, "photo.jpg", "binary junk that is not a playlist"));
        assertEquals("200", res[0]);
        assertTrue("a non-playlist must be refused: " + res[1], res[1].contains("\"success\":false"));
        assertEquals("nothing may be activated", "", AppConfig.getM3uFilePath());
    }

    @Test
    public void upload_acceptsATxtPlaylistWithoutTheExtM3uHeader() throws Exception {
        String boundary = "----LiteTVTest";
        String txt = "央视频道,#genre#\nCCTV1,http://stream/one\n";
        String[] res = request("POST", "/config/m3u-file-upload",
                "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n",
                multipartBody(boundary, "list.txt", txt));
        assertTrue(res[1].contains("\"success\":true"));
    }

    @Test
    public void upload_rejectsMultipartOnFormEndpoints() throws Exception {
        String boundary = "----LiteTVTest";
        String[] res = request("POST", "/config/m3u-url",
                "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n",
                multipartBody(boundary, "x.m3u", "#EXTM3U\n"));
        assertEquals("415", res[0]);
    }

    @Test
    public void upload_crossSiteIsRejected() throws Exception {
        String boundary = "----LiteTVTest";
        String[] res = request("POST", "/config/m3u-file-upload",
                "Content-Type: multipart/form-data; boundary=" + boundary + "\r\n"
                        + "Origin: http://evil.example\r\n",
                multipartBody(boundary, "x.m3u", "#EXTM3U\n"));
        assertEquals("403", res[0]);
    }

    /** Parser-level check so a failure points at the multipart handling, not at HTTP plumbing. */
    @Test
    public void parseMultipartFile_extractsNameAndExactBytes() {
        String boundary = "----LiteTVTest";
        byte[] body = multipartBody(boundary, "a.m3u", "#EXTM3U\n#EXTINF:-1,CCTV1\n").getBytes(StandardCharsets.UTF_8);
        ConfigWebServer.MultipartFile parsed = ConfigWebServer.parseMultipartFile(
                body, "multipart/form-data; boundary=" + boundary);
        assertTrue("parser returned null for body: "
                + new String(body, StandardCharsets.ISO_8859_1).replace("\r", "\\r").replace("\n", "\\n"),
                parsed != null);
        assertEquals("a.m3u", parsed.fileName);
        assertEquals("#EXTM3U\n#EXTINF:-1,CCTV1\n", new String(parsed.content, StandardCharsets.UTF_8));
    }


    @Test
    public void parseMultipartFile_restoresAUtf8FileName() {
        String boundary = "----LiteTVTest";
        byte[] body = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"\u4e0a\u4f20.m3u\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + "#EXTM3U\n"
                + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        ConfigWebServer.MultipartFile parsed = ConfigWebServer.parseMultipartFile(
                body, "multipart/form-data; boundary=" + boundary);
        assertTrue(parsed != null);
        assertEquals("\u4e0a\u4f20.m3u", parsed.fileName);
    }

    /** Builds a one-file multipart/form-data body matching the boundary. */
    private static String multipartBody(String boundary, String fileName, String content) {
        return "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n"
                + content
                + "\r\n--" + boundary + "--\r\n";
    }

    // ---- /config/get ----

    @Test
    public void playbackPost_savesValidSettingsAndReportsThemBack() throws Exception {
        String[] res = request("POST", "/config/playback",
                "Content-Type: application/x-www-form-urlencoded\r\n",
                "video_render_mode=texture&video_decode_mode=software&video_tunneling=1");
        assertEquals("200", res[0]);
        assertTrue("must report success, got " + res[1], res[1].contains("\"success\":true"));
        assertEquals(AppConfig.RENDER_TEXTURE, AppConfig.getVideoRenderMode());
        assertEquals(xyz.doikki.videoplayer.player.VideoViewConfig.DECODE_SOFTWARE,
                AppConfig.getVideoDecodeMode());
        assertTrue(AppConfig.isTunnelingEnabled());

        String[] config = request("GET", "/config/get", null, null);
        assertTrue("the page must be able to read them back: " + config[1],
                config[1].contains("\"video_render_mode\":\"texture\""));
        assertTrue(config[1].contains("\"video_decode_mode\":\"software\""));
        assertTrue(config[1].contains("\"video_tunneling\":true"));
    }

    @Test
    public void playbackPost_rejectsAnInvalidValueWithoutWritingAnything() throws Exception {
        AppConfig.setVideoRenderMode(AppConfig.RENDER_SURFACE);
        String[] res = request("POST", "/config/playback",
                "Content-Type: application/x-www-form-urlencoded\r\n",
                "video_render_mode=texture&video_decode_mode=quantum");
        assertEquals("200", res[0]);
        assertTrue("invalid input must be rejected: " + res[1], res[1].contains("\"success\":false"));
        assertEquals("a partial write would be worse than a rejection", AppConfig.RENDER_SURFACE,
                AppConfig.getVideoRenderMode());
    }

    @Test
    public void displayPost_savesEpgToggleAndOpacity() throws Exception {
        String[] res = request("POST", "/config/display",
                "Content-Type: application/x-www-form-urlencoded\r\n",
                "epg_display_enabled=0&sidebar_opacity=2");
        assertEquals("200", res[0]);
        assertTrue(res[1].contains("\"success\":true"));
        assertTrue(!AppConfig.isEpgDisplayEnabled());
        assertEquals(AppConfig.SIDEBAR_OPAQUE, AppConfig.getSidebarOpacity());
    }

    @Test
    public void displayPost_rejectsOutOfRangeOpacity() throws Exception {
        AppConfig.setSidebarOpacity(AppConfig.SIDEBAR_TRANSLUCENT);
        String[] res = request("POST", "/config/display",
                "Content-Type: application/x-www-form-urlencoded\r\n",
                "sidebar_opacity=9");
        assertTrue(res[1].contains("\"success\":false"));
        assertEquals(AppConfig.SIDEBAR_TRANSLUCENT, AppConfig.getSidebarOpacity());
    }

    @Test
    public void getConfig_reportsLocalFilePath() throws Exception {
        AppConfig.setM3uFilePath("/data/files/playlists/local.m3u");
        String[] res = request("GET", "/config/get", null, null);

        assertEquals("200", res[0]);
        assertTrue("JSON must include m3u_file_path",
                res[1].contains("\"m3u_file_path\":\"/data/files/playlists/local.m3u\""));
        assertTrue("JSON must include m3u_url", res[1].contains("\"m3u_url\""));
    }

    // ---- /config/m3u-file-clear ----

    @Test
    public void clearRemovesFileSettingAndNotifies() throws Exception {
        // Prepare a fake copied file exactly where the settings screen would put it.
        Context context = RuntimeEnvironment.getApplication();
        File dir = new File(context.getFilesDir(), "playlists");
        assertTrue(dir.mkdirs() || dir.exists());
        File local = new File(dir, "local.m3u");
        try (FileOutputStream f = new FileOutputStream(local)) {
            f.write("#EXTM3U\n".getBytes(StandardCharsets.UTF_8));
        }
        AppConfig.setM3uFilePath(local.getAbsolutePath());

        String[] res = request("POST", "/config/m3u-file-clear",
                "Content-Type: application/x-www-form-urlencoded\r\n", "action=clear");

        assertEquals("200", res[0]);
        assertTrue(res[1].contains("\"success\":true"));
        assertEquals("", AppConfig.getM3uFilePath());
        assertEquals("", mLastFileChange.get());
        assertTrue("the copied file must be deleted", !local.exists());
    }

    // ---- guards still work ----

    @Test
    public void crossSitePost_rejected403() throws Exception {
        String[] res = request("POST", "/config/m3u-file-clear",
                "Content-Type: application/x-www-form-urlencoded\r\n"
                        + "Origin: http://evil.example\r\n",
                "action=clear");
        assertEquals("403", res[0]);
    }

    @Test
    public void sameOriginPost_allowed() throws Exception {
        String[] res = request("POST", "/config/m3u-file-clear",
                "Content-Type: application/x-www-form-urlencoded\r\n"
                        + "Origin: http://localhost:" + mPort + "\r\n",
                "action=clear");
        assertEquals("200", res[0]);
    }

    @Test
    public void wrongContentType_rejected415() throws Exception {
        String[] res = request("POST", "/config/m3u-file-clear",
                "Content-Type: text/plain\r\n", "action=clear");
        assertEquals("415", res[0]);
    }

    @Test
    public void unknownPath_404() throws Exception {
        String[] res = request("GET", "/config/nope", null, null);
        assertEquals("404", res[0]);
    }

    // ---- update endpoints ----

    @Test
    public void displayPost_savesTheReplayBadgeSwitch() throws Exception {
        AppConfig.setCatchupBadgeEnabled(true);

        String[] res = request("POST", "/config/display",
                "Content-Type: application/x-www-form-urlencoded\r\n", "catchup_badge=0");
        assertEquals("200", res[0]);
        assertTrue(res[1].contains("\"success\":true"));
        assertFalse("the switch must actually change", AppConfig.isCatchupBadgeEnabled());
        assertTrue("the player must be told, or the picture keeps the old setting",
                mDisplayChangeSeen);

        String[] config = request("GET", "/config/get", null, null);
        assertTrue("the page must be able to read it back: " + config[1],
                config[1].contains("\"catchup_badge\":false"));
        AppConfig.setCatchupBadgeEnabled(true);
    }

    @Test
    public void updateSettingsPost_savesTheAutoCheckSwitchAndReportsItBack() throws Exception {
        AppConfig.setUpdateAutoCheckEnabled(true);
        String[] res = request("POST", "/config/update-settings",
                "Content-Type: application/x-www-form-urlencoded\r\n", "update_auto_check=0");
        assertEquals("200", res[0]);
        assertTrue(res[1].contains("\"success\":true"));
        assertTrue("the switch must actually change", !AppConfig.isUpdateAutoCheckEnabled());

        String[] config = request("GET", "/config/get", null, null);
        assertTrue("the page must be able to read it back: " + config[1],
                config[1].contains("\"update_auto_check\":false"));
    }

    @Test
    public void updateSettingsPost_rejectsAnInvalidValueWithoutWritingAnything() throws Exception {
        AppConfig.setUpdateAutoCheckEnabled(true);
        String[] res = request("POST", "/config/update-settings",
                "Content-Type: application/x-www-form-urlencoded\r\n", "update_auto_check=maybe");
        assertTrue("invalid input must be rejected: " + res[1], res[1].contains("\"success\":false"));
        assertTrue("nothing may be written on rejection", AppConfig.isUpdateAutoCheckEnabled());
    }

    @Test
    public void getConfig_reportsTheInstalledVersionAndUpdateState() throws Exception {
        String[] res = request("GET", "/config/get", null, null);
        assertEquals("200", res[0]);
        assertTrue("the page shows the TV's version: " + res[1], res[1].contains("\"app_version_name\":"));
        assertTrue(res[1].contains("\"app_version_code\":"));
        assertTrue(res[1].contains("\"update_state\":"));
        assertTrue(res[1].contains("\"update_progress\":"));
        assertTrue(res[1].contains("\"update_auto_check\":"));
    }

    @Test
    public void updateInstallPost_refusesWhenNothingIsKnownToUpdate() throws Exception {
        // The manager has not checked in this process, so there is nothing to install; the endpoint
        // must say so instead of starting a download of nothing. No network is touched here.
        String[] res = request("POST", "/config/update-install",
                "Content-Type: application/x-www-form-urlencoded\r\n", "");
        assertEquals("200", res[0]);
        assertTrue("got " + res[1], res[1].contains("\"success\":false"));
        assertTrue("the reason must name the problem: " + res[1],
                res[1].contains("No update to install yet"));
    }

    @Test
    public void updateStateJson_tellsThePageWhatIsHappening() throws Exception {
        // Checked directly because the alternative -- driving the real singleton -- needs a network.
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        ConfigWebServer.addUpdateStateJson(json,
                com.cabletv.player.update.UpdateStatus.idle());
        assertEquals("idle", json.get("update_state").getAsString());
        assertEquals(0, json.get("update_progress").getAsInt());
        assertEquals("", json.get("update_error").getAsString());

        com.cabletv.player.update.UpdateManifest manifest = com.cabletv.player.update.UpdateManifest.parse(
                "{\"versionCode\":7,\"versionName\":\"1.5.0\",\"changelog\":[\"note\"],\"variants\":{"
                        + "\"x86\":{\"url\":\"https://github.com/vibe4free/LiteTV/releases/download/"
                        + "v1.5.0/LiteTV.apk\",\"sha256\":\"" + SHA + "\",\"size\":1000}}}");
        com.google.gson.JsonObject available = new com.google.gson.JsonObject();
        ConfigWebServer.addUpdateStateJson(available, com.cabletv.player.update.UpdateStatus.available(
                manifest, manifest.variantFor(new String[]{"x86"}), 1L));
        assertEquals("update_available", available.get("update_state").getAsString());
        assertEquals("1.5.0", available.get("update_latest_version").getAsString());
        assertEquals(1, available.getAsJsonArray("update_changelog").size());

        com.google.gson.JsonObject downloading = new com.google.gson.JsonObject();
        ConfigWebServer.addUpdateStateJson(downloading,
                com.cabletv.player.update.UpdateStatus.available(manifest,
                        manifest.variantFor(new String[]{"x86"}), 1L).withProgress(250L, 1000L));
        assertEquals("downloading", downloading.get("update_state").getAsString());
        assertEquals(25, downloading.get("update_progress").getAsInt());

        com.google.gson.JsonObject failed = new com.google.gson.JsonObject();
        ConfigWebServer.addUpdateStateJson(failed, com.cabletv.player.update.UpdateStatus.error(
                com.cabletv.player.update.UpdateError.HTTP_NOT_FOUND, 1L));
        assertEquals("error", failed.get("update_state").getAsString());
        assertEquals("HTTP_NOT_FOUND", failed.get("update_error").getAsString());
    }

    private static final String SHA = "00112233445566778899aabbccddeeff"
            + "00112233445566778899aabbccddeeff";
}