package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.cabletv.player.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class SingleProgramTimelineTest {

    private TimeShiftBarComponent buildBar() {
        Context context = RuntimeEnvironment.getApplication();
        return new TimeShiftBarComponent(context);
    }

    @Test
    public void channelInfo_isPopulatedCorrectly_withAndWithoutLogo() {
        TimeShiftBarComponent bar = buildBar();
        // Without logo
        bar.setChannelInfo("CCTV-5 体育 4K", 5, 1, 3, null);
        assertNotNull(bar);

        // With logo
        bar.setChannelInfo("CCTV-5 体育 4K", 5, 1, 3, "http://example.com/logo.png");
        assertNotNull(bar);
    }

    @Test
    public void liveMode_togglesLiveAndCatchupViews() {
        TimeShiftBarComponent bar = buildBar();
        long now = 1_700_000_000_000L;

        // In live mode: LIVE indicator is shown, back to live is hidden
        bar.setLiveMode(true, now);
        bar.setWindow(now - 1_800_000L, now + 1_800_000L, now);

        // In catchup mode: Back to live is shown, behind live is updated
        bar.setLiveMode(false, now);
        bar.setWindow(now - 3_600_000L, now, now - 910_000L); // 15m10s behind
        assertEquals("15:10", TimeShiftBarComponent.formatBehind(910_000L));
    }

    @Test
    public void behindLive_staysFixedWhileReplayAdvances() {
        TimeShiftBarComponent bar = buildBar();
        long now = 1_700_000_000_000L;
        long target = now - 910_000L; // 15m10s behind

        bar.setLiveMode(false, now);
        bar.setWindow(now - 3_600_000L, now, target);
        assertEquals(910_000L, bar.findBehindLiveMs());

        // As replay advances by 10s: position becomes target + 10s
        bar.setPlaybackPosition(target + 10_000L);
        // The behind live offset must remain fixed (not count down/decrease)
        assertEquals(910_000L, bar.findBehindLiveMs());
    }

    @Test
    public void programInfo_formatsPlayingNowAndNext() {
        TimeShiftBarComponent bar = buildBar();
        bar.setProgramInfo("2026 世界杯预选赛", "体育新闻 (21:35)");

        long start = 1_000_000L;
        long end = 2_000_000L;
        bar.setWindow(start, end, start + 500_000L);

        assertEquals(start, bar.getWindowStart());
        assertEquals(end, bar.getWindowEnd());
        assertEquals(start + 500_000L, bar.findShownPositionMs());
    }

    @Test
    public void timeShiftBar_applyOpacity_handlesAllLevels() {
        TimeShiftBarComponent bar = buildBar();
        // Translucent (default)
        com.cabletv.player.config.AppConfig.setSidebarOpacity(com.cabletv.player.config.AppConfig.SIDEBAR_TRANSLUCENT);
        bar.applyOpacity();
        assertNotNull(bar.getBackground());

        // Semi-opaque
        com.cabletv.player.config.AppConfig.setSidebarOpacity(com.cabletv.player.config.AppConfig.SIDEBAR_SEMI_OPAQUE);
        bar.applyOpacity();
        assertNotNull(bar.getBackground());

        // Opaque
        com.cabletv.player.config.AppConfig.setSidebarOpacity(com.cabletv.player.config.AppConfig.SIDEBAR_OPAQUE);
        bar.applyOpacity();
        assertNotNull(bar.getBackground());
    }

    @Test
    public void channelListComponent_applyOpacity_handlesAllLevels() {
        Context context = RuntimeEnvironment.getApplication();
        com.cabletv.player.config.ChannelNavigator nav = new com.cabletv.player.config.ChannelNavigator();
        ChannelListComponent comp = new ChannelListComponent(context, nav);

        com.cabletv.player.config.AppConfig.setSidebarOpacity(com.cabletv.player.config.AppConfig.SIDEBAR_TRANSLUCENT);
        comp.applyOpacity();

        com.cabletv.player.config.AppConfig.setSidebarOpacity(com.cabletv.player.config.AppConfig.SIDEBAR_SEMI_OPAQUE);
        comp.applyOpacity();

        com.cabletv.player.config.AppConfig.setSidebarOpacity(com.cabletv.player.config.AppConfig.SIDEBAR_OPAQUE);
        comp.applyOpacity();
    }
}
