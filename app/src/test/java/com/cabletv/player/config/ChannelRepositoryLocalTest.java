package com.cabletv.player.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.Looper;

import com.cabletv.player.model.Channel;
import com.cabletv.player.model.ChannelGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.robolectric.Shadows.shadowOf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end-ish tests of the local-M3U-file feature at the repository level: a file picked
 * by the settings screen lands in app storage and ChannelRepository must load it, prefer it
 * over the URL, report failures cleanly, and remember its embedded EPG URLs.
 */
@RunWith(RobolectricTestRunner.class)
public class ChannelRepositoryLocalTest {

    private static final String LOCAL_PLAYLIST =
            "#EXTM3U x-tvg-url=\"http://epg.example.com/e.xml.gz\"\n"
            + "#EXTINF:-1 tvg-id=\"c1\" group-title=\"央视频道\" tvg-logo=\"http://l/c1.png\",CCTV-1\n"
            + "http://stream/cctv1\n"
            + "#EXTINF:-1 group-title=\"央视频道\",CCTV-2\n"
            + "http://stream/cctv2\n"
            + "#EXTINF:-1 group-title=\"体育\",CCTV-5\n"
            + "http://stream/cctv5\n";

    private Context mContext;
    private File mLocalFile;
    private final AtomicReference<List<ChannelGroup>> mChannels = new AtomicReference<>();
    private final AtomicReference<String> mLoadFailure = new AtomicReference<>();
    private final AtomicBoolean mFailureServedFromCache = new AtomicBoolean(false);

    @Before
    public void setUp() throws Exception {
        mContext = RuntimeEnvironment.getApplication();
        mLocalFile = new File(mContext.getCacheDir(), "local-test.m3u");
        writeUtf8(mLocalFile, LOCAL_PLAYLIST);
        // Reset stored config so the previous test cannot leak into this one.
        AppConfig.setM3uUrl("");
        AppConfig.setM3uFilePath("");
        AppConfig.setM3uEpgUrls(java.util.Collections.emptyList());
    }

    @After
    public void tearDown() {
        //noinspection ResultOfMethodCallIgnored
        mLocalFile.delete();
    }

    private ChannelRepository newRepository() {
        ChannelRepository repo = new ChannelRepository(mContext);
        repo.addListener(channels -> mChannels.set(channels));
        repo.setOnLoadFailedListener((reason, fromCache) -> {
            mLoadFailure.set(reason);
            mFailureServedFromCache.set(fromCache);
        });
        return repo;
    }

    /** Reloads and waits until the repository finished (channels set or load failure reported). */
    private void reloadAndWait(ChannelRepository repo) {
        mChannels.set(null);
        mLoadFailure.set(null);
        repo.reload();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            if (mChannels.get() != null || mLoadFailure.get() != null) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("Reload did not finish within 15s");
    }

    /**
     * reload() with no callback expected (no source configured): the repository stays quiet
     * by design, so just give the background thread time to finish and idle the main loop.
     */
    private void reloadAndSettle(ChannelRepository repo) {
        repo.reload();
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---- the feature under test: a file on disk is the playlist ----

    @Test
    public void load_fromLocalFile_parsesChannelsAndEmbeddedEpg() {
        AppConfig.setM3uFilePath(mLocalFile.getAbsolutePath());
        ChannelRepository repo = newRepository();
        reloadAndWait(repo);

        List<ChannelGroup> groups = mChannels.get();
        assertNotNull("channels must be published", groups);
        assertEquals(2, groups.size()); // 央视频道 + 体育
        ChannelGroup tv = groups.get(0);
        assertEquals("央视频道", tv.name);
        assertEquals(2, tv.channels.size());
        assertEquals("CCTV-1", tv.channels.get(0).name);
        assertEquals("http://stream/cctv1", tv.channels.get(0).url());

        ChannelGroup sports = groups.get(1);
        assertEquals("体育", sports.name);
        assertEquals(1, sports.channels.size());
        assertEquals("CCTV-5", sports.channels.get(0).name);
        assertEquals("c1", tv.channels.get(0).tvgId);

        // Embedded EPG URL from the #EXTM3U header must be remembered for the EPG manager.
        assertEquals(java.util.Collections.singletonList("http://epg.example.com/e.xml.gz"),
                AppConfig.getM3uEpgUrls());

        // The flat view stays in playlist order and indexes by URL.
        assertEquals(3, repo.getChannelCount());
        assertEquals(0, repo.getCurrentChannelIndex("http://stream/cctv1"));
        assertEquals(2, repo.getCurrentChannelIndex("http://stream/cctv5"));
    }

    @Test
    public void load_localFileTakesPriorityOverUrl() {
        // Broad-brush: the local file must be used even though a URL is also configured.
        // The URL points at a dead port; were it consulted the load would fail instead.
        AppConfig.setM3uUrl("http://127.0.0.1:1/playlist.m3u");
        AppConfig.setM3uFilePath(mLocalFile.getAbsolutePath());
        ChannelRepository repo = newRepository();
        reloadAndWait(repo);

        List<ChannelGroup> groups = mChannels.get();
        assertNotNull("local file must win over the configured URL", groups);
        assertEquals(3, repo.getChannelCount());
        assertNull(mLoadFailure.get());
    }

    /**
     * Reloading a playlist must not lose the channel that is playing. Without a "not found"
     * answer, a reload looked identical to "you are on channel 0", so editing the source or
     * flipping the merge switch dropped the viewer onto the first channel.
     */
    @Test
    public void findChannelIndex_pointsAtThePlayingChannel() {
        AppConfig.setM3uFilePath(mLocalFile.getAbsolutePath());
        ChannelRepository repo = newRepository();
        reloadAndWait(repo);

        assertEquals(2, repo.findChannelIndex("http://stream/cctv5"));
        assertEquals("a channel that is gone must not be reported as index 0",
                -1, repo.findChannelIndex("http://stream/vanished"));
        assertEquals(-1, repo.findChannelIndex(""));
    }

    @Test
    public void load_bomAndCrLf_stillParses() throws Exception {
        // A file written by Windows Notepad: UTF-8 BOM + CRLF line endings.
        File bomFile = new File(mContext.getCacheDir(), "local-bom.m3u");
        writeUtf8(bomFile, "\uFEFF#EXTM3U\r\n"
                + "#EXTINF:-1 group-title=\"G\",A\r\n"
                + "http://stream/a\r\n");
        try {
            AppConfig.setM3uFilePath(bomFile.getAbsolutePath());
            ChannelRepository repo = newRepository();
            reloadAndWait(repo);
            assertNotNull(mChannels.get());
            assertEquals(1, mChannels.get().get(0).channels.size());
            assertEquals("A", mChannels.get().get(0).channels.get(0).name);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            bomFile.delete();
        }
    }

    // ---- failure handling ----

    @Test
    public void load_garbageFile_reportsFailureWithoutChannels() throws Exception {
        File garbage = new File(mContext.getCacheDir(), "local-garbage.m3u");
        writeUtf8(garbage, "this is not a playlist at all 中文内容\nsecond line");
        try {
            AppConfig.setM3uFilePath(garbage.getAbsolutePath());
            ChannelRepository repo = newRepository();
            reloadAndWait(repo);

            assertNull(mChannels.get());
            assertNotNull("a failure must be reported", mLoadFailure.get());
            assertTrue("no cache to serve from", !mFailureServedFromCache.get());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            garbage.delete();
        }
    }

    @Test
    public void load_missingFileAndNoUrl_reportsNothingAndNoCrash() {
        AppConfig.setM3uFilePath("/nonexistent/path/local.m3u");
        ChannelRepository repo = newRepository();
        reloadAndSettle(repo);
        assertNull(mChannels.get());
        assertNull(mLoadFailure.get());
    }

    @Test
    public void load_clearedFileConfig_staysEmpty() {
        // After "移除本地文件" the path is empty and no source is configured: no crash, no channels.
        AppConfig.setM3uFilePath("");
        ChannelRepository repo = newRepository();
        reloadAndSettle(repo);
        assertNull(mChannels.get());
        assertNull(mLoadFailure.get());
    }

    /** TXT files (name,url#url) are accepted from a local file too. */
    @Test
    public void load_localTxtFile_parses() throws Exception {
        File txt = new File(mContext.getCacheDir(), "local.txt");
        writeUtf8(txt, "新闻频道#genre#\n央视1,http://s/1#http://s/2\n");
        try {
            AppConfig.setM3uFilePath(txt.getAbsolutePath());
            ChannelRepository repo = newRepository();
            reloadAndWait(repo);
            assertNotNull(mChannels.get());
            List<Channel> flat = repo.getAllChannels();
            assertEquals(1, flat.size());
            assertEquals("央视1", flat.get(0).name);
            assertEquals(2, flat.get(0).urls.size());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            txt.delete();
        }
    }

    private static void writeUtf8(File file, String content) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            out.close();
        }
    }
}