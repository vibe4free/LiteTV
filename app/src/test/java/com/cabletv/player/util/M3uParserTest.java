package com.cabletv.player.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cabletv.player.model.Channel;
import com.cabletv.player.model.ChannelGroup;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * Parsing tests for the content path a local M3U file takes: it is read from disk and handed
 * to {@link M3uParser#parse(String)} as one UTF-8 string (BOM stripped by the repository).
 * These cases mirror what a real file on a USB drive / internal storage contains.
 */
@RunWith(RobolectricTestRunner.class)
public class M3uParserTest {

    private static final String FULL_M3U = "#EXTM3U\r\n"
            + "#EXTINF:-1 tvg-id=\"cctv1\" tvg-name=\"CCTV1\" tvg-logo=\"http://logo/cctv1.png\" "
            + "group-title=\"央视\" http-user-agent=\"MyUA\" http-referrer=\"https://ref/\",CCTV1\r\n"
            + "http://stream/cctv1\r\n"
            + "#EXTINF:-1 tvg-id=\"cctv2\" tvg-logo=\"http://logo/cctv2.png\",CCTV2\r\n"
            + "http://stream/cctv2\r\n"
            + "#EXTINF:-1,新闻\r\n"
            + "http://stream/xinwen\r\n";

    /**
     * Playlists in the wild separate guide addresses with commas as often as with spaces. A comma
     * left attached to an address makes the download fail, and nothing says why -- the guide is
     * just empty.
     */
    /**
     * VLC puts its options above the entry they belong to; they used to be dropped there because
     * no channel existed yet, and the source then failed on a server that wants a user agent.
     */
    /**
     * The address a browser shows for a file on a code host is a page about that file. Copying it
     * is the obvious thing to do, and the result was "could not be parsed" with nothing else.
     */
    @Test
    public void aWebPageAddressIsTurnedIntoTheFileAddress() {
        assertEquals("https://raw.githubusercontent.com/some-owner/some-repo/master/list.m3u",
                M3uParser.normalizePlaylistUrl(
                        "https://github.com/some-owner/some-repo/blob/master/list.m3u"));
        assertEquals("https://raw.githubusercontent.com/some-owner/some-repo/main/sub/list.m3u8",
                M3uParser.normalizePlaylistUrl(
                        "https://github.com/some-owner/some-repo/blob/main/sub/list.m3u8"));
    }

    @Test
    public void ordinaryAddressesAreLeftAlone() {
        String direct = "http://192.168.1.10:5140/playlist.m3u";
        assertEquals(direct, M3uParser.normalizePlaylistUrl(direct));
        String raw = "https://raw.githubusercontent.com/o/r/master/list.m3u";
        assertEquals(raw, M3uParser.normalizePlaylistUrl(raw));
        assertEquals("surrounding spaces are trimmed",
                direct, M3uParser.normalizePlaylistUrl("  " + direct + "  "));
    }

    @Test
    public void aPageIsRecognisedSoTheFailureCanBeExplained() {
        assertTrue(M3uParser.looksLikeWebPage("<!DOCTYPE html>\n<html lang=\"en\">"));
        assertTrue(M3uParser.looksLikeWebPage("\n\n  <html><head></head></html>"));
        assertFalse(M3uParser.looksLikeWebPage("#EXTM3U\n#EXTINF:-1,A\nhttp://s/1\n"));
        assertFalse(M3uParser.looksLikeWebPage(""));
        assertFalse(M3uParser.looksLikeWebPage(null));
    }

    @Test
    public void vlcOptionsBeforeTheEntryBelongToThatEntry() {
        String playlist = "#EXTM3U\n"
                + "#EXTVLCOPT:http-user-agent=BeforeUA\n"
                + "#EXTINF:-1,First\nhttp://s/1\n"
                + "#EXTINF:-1,Second\nhttp://s/2\n";

        java.util.List<ChannelGroup> groups = M3uParser.parse(playlist);

        Channel first = groups.get(0).channels.get(0);
        Channel second = groups.get(0).channels.get(1);
        assertEquals("BeforeUA", first.headers.get("User-Agent"));
        assertTrue("the option must not leak into the next channel",
                second.headers == null || !second.headers.containsKey("User-Agent"));
    }

    @Test
    public void epgUrls_separatedByCommasAreAllReadable() {
        String playlist = "#EXTM3U x-tvg-url=\"https://a.example/e1.xml.gz, https://b.example/e2.xml.gz,"
                + "https://c.example/e3.xml.gz\"\n"
                + "#EXTINF:-1,One\nhttp://s/1\n";

        java.util.List<String> urls = M3uParser.parseEpgUrls(playlist, "https://list.example/p.m3u");

        assertEquals(3, urls.size());
        assertEquals("https://a.example/e1.xml.gz", urls.get(0));
        assertEquals("https://b.example/e2.xml.gz", urls.get(1));
        assertEquals("https://c.example/e3.xml.gz", urls.get(2));
    }

    @Test
    public void epgUrls_mixedSeparatorsAndTrailingPunctuationAreHandled() {
        String playlist = "#EXTM3U url-tvg=\"https://a.example/e1.xml.gz, "
                + "https://b.example/e2.xml.gz;https://c.example/e3.xml.gz\"\n"
                + "#EXTINF:-1,One\nhttp://s/1\n";

        java.util.List<String> urls = M3uParser.parseEpgUrls(playlist, "https://list.example/p.m3u");

        assertEquals(3, urls.size());
        for (String url : urls) {
            assertTrue("trailing punctuation must not survive: " + url,
                    url.endsWith(".xml.gz"));
        }
    }

    @Test
    public void parse_m3uWithAttributes_extractsChannelFields() {
        List<ChannelGroup> groups = M3uParser.parse(FULL_M3U);

        assertEquals(1, groups.size());
        ChannelGroup group = groups.get(0);
        assertEquals("央视", group.name);
        assertEquals(3, group.channels.size());

        Channel first = group.channels.get(0);
        assertEquals("CCTV1", first.name);
        assertEquals("央视", first.group);
        assertEquals("http://logo/cctv1.png", first.logo);
        assertEquals("cctv1", first.tvgId);
        assertNotNull(first.headers);
        assertEquals("MyUA", first.headers.get(M3uParser.HEADER_USER_AGENT));
        assertEquals("https://ref/", first.headers.get(M3uParser.HEADER_REFERER));
        assertEquals(1, first.urls.size());
        assertEquals("http://stream/cctv1", first.url());
    }

    @Test
    public void parse_channelWithoutGroupTitle_inheritsPreviousGroup() {
        List<ChannelGroup> groups = M3uParser.parse(FULL_M3U);

        Channel second = groups.get(0).channels.get(1);
        assertEquals("CCTV2", second.name);
        assertEquals("央视", second.group); // inherits the group of the channel above it
    }

    @Test
    public void parse_emptyOrNull_returnsEmpty() {
        assertTrue(M3uParser.parse(null).isEmpty());
        assertTrue(M3uParser.parse("").isEmpty());
        assertTrue(M3uParser.parse("   \n  ").isEmpty());
    }

    @Test
    public void parse_commentsAndBlankLines_areIgnored() {
        String content = "#EXTM3U\n"
                + "# some comment\n"
                + "\n"
                + "#EXTINF:-1,Only\n"
                + "http://stream/only\n"
                + "# another comment\n";
        List<ChannelGroup> groups = M3uParser.parse(content);
        assertEquals(1, groups.size());
        assertEquals("Only", groups.get(0).channels.get(0).name);
    }

    @Test
    public void parse_urlWithoutScheme_isDropped() {
        // A bare path (no http/https) must not become a channel anyone tries to play.
        String content = "#EXTM3U\n"
                + "#EXTINF:-1,Relative\n"
                + "rtmp://nope\n"
                + "#EXTINF:-1,Good\n"
                + "http://stream/good\n";
        List<ChannelGroup> groups = M3uParser.parse(content);
        assertEquals(1, groups.get(0).channels.size());
        assertEquals("Good", groups.get(0).channels.get(0).name);
    }

    @Test
    public void parse_catchupAttributes_captured() {
        String content = "#EXTM3U catchup=\"default\" catchup-source=\"http://ts/&utc=${start}\"\n"
                + "#EXTINF:-1 catchup=\"append\" group-title=\"G\",C1\n"
                + "http://stream/c1\n"
                + "#EXTINF:-1,C2\n"
                + "http://stream/c2\n";
        List<ChannelGroup> groups = M3uParser.parse(content);
        Channel c1 = groups.get(0).channels.get(0);
        assertEquals("append", c1.catchupType); // per-channel wins over the header default
        assertEquals("http://ts/&utc=${start}", c1.catchupSource);
        assertEquals("", c1.catchupReplace); // none declared on the channel or the header
        Channel c2 = groups.get(0).channels.get(1);
        assertEquals("default", c2.catchupType); // inherited from the #EXTM3U header
        assertEquals("http://ts/&utc=${start}", c2.catchupSource);
    }

    @Test
    public void parse_vlcOptLines_setHeaders() {
        String content = "#EXTM3U\n"
                + "#EXTINF:-1,WithVlcOpts\n"
                + "#EXTVLCOPT:http-user-agent=VLC-UA\n"
                + "#EXTVLCOPT:http-referrer=https://vlc-ref/\n"
                + "http://stream/vlc\n";
        Channel channel = M3uParser.parse(content).get(0).channels.get(0);
        assertEquals("VLC-UA", channel.headers.get(M3uParser.HEADER_USER_AGENT));
        assertEquals("https://vlc-ref/", channel.headers.get(M3uParser.HEADER_REFERER));
    }

    @Test
    public void parse_inlineHeadersAfterUrlPipe_areExtracted() {
        String content = "#EXTM3U\n"
                + "#EXTINF:-1,Inline\n"
                + "http://stream/inline|User-Agent=UA-X&Referer=https://r/\n";
        Channel channel = M3uParser.parse(content).get(0).channels.get(0);
        assertEquals("http://stream/inline", channel.url());
        assertEquals("UA-X", channel.headers.get(M3uParser.HEADER_USER_AGENT));
        assertEquals("https://r/", channel.headers.get(M3uParser.HEADER_REFERER));
    }

    @Test
    public void parse_txtGenreFormat_multiSource_supported() {
        // Cover both genre-line spellings a local file may use.
        String content = "央视频道#genre#\n"
                + "电影1,http://s/1#http://s/2#rtmp://bad\n"
                + "体育,#genre#\n"
                + "篮球,http://n/1\n";
        List<ChannelGroup> groups = M3uParser.parse(content);
        assertEquals(2, groups.size());
        assertEquals("央视频道", groups.get(0).name);       // comma-less variant
        assertEquals("体育", groups.get(1).name);            // traditional comma variant
        Channel movie = groups.get(0).channels.get(0);
        assertEquals("电影1", movie.name);
        // Two http sources survive; the rtmp one is dropped by the scheme filter.
        assertEquals(2, movie.urls.size());
        assertEquals("http://s/1", movie.urls.get(0));
        assertEquals("http://s/2", movie.urls.get(1));
    }

    // ---- embedded EPG URLs (headline feature for local playlists) ----

    @Test
    public void parseEpgUrls_explicitHttp_kept_relativeDroppedForLocalFile() {
        String header = "#EXTM3U x-tvg-url=\"http://epg/a.xml.gz | https://epg/b.xml\" tvg-url=\"rel/x.xml\"";
        List<String> urls = M3uParser.parseEpgUrls(header, ""); // "" = local file has no base URL
        assertEquals(2, urls.size());
        assertEquals("http://epg/a.xml.gz", urls.get(0));
        assertEquals("https://epg/b.xml", urls.get(1));
    }

    @Test
    public void parseEpgUrls_relativeResolvedAgainstHttpPlaylist() {
        List<String> urls = M3uParser.parseEpgUrls(
                "#EXTM3U url-tvg=\"epg/e.xml.gz\"", "http://host/playlist.m3u");
        assertEquals(1, urls.size());
        assertEquals("http://host/epg/e.xml.gz", urls.get(0));
    }

    @Test
    public void parseEpgUrls_missingHeader_empty() {
        assertTrue(M3uParser.parseEpgUrls("not a play list\n", "").isEmpty());
        assertTrue(M3uParser.parseEpgUrls("#EXTM3U\n#EXTINF:-1,C\nhttp://x\n", "").isEmpty());
    }

    @Test
    public void parseEpgUrls_duplicates_removed() {
        List<String> urls = M3uParser.parseEpgUrls(
                "#EXTM3U x-tvg-url=\"http://epg/a.xml|http://epg/a.xml\"", "");
        assertEquals(1, urls.size());
        assertEquals("http://epg/a.xml", urls.get(0));
    }

    @Test
    public void parseEpgUrls_capped() {
        String many = "#EXTM3U x-tvg-url=\"http://e/1|http://e/2|http://e/3|http://e/4|http://e/5|http://e/6\"";
        assertEquals(5, M3uParser.parseEpgUrls(many, "").size());
    }
}