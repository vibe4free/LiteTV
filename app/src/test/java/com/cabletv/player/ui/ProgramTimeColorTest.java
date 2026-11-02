package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cabletv.player.epg.EpgManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The guide's list recycles rows. A view that rendered the programme on air used to keep the
 * highlight for whatever it was bound to next, so a guide could show several "current" rows at
 * once (reported from a television, with two entries highlighted minutes apart).
 */
@RunWith(RobolectricTestRunner.class)
public class ProgramTimeColorTest {

    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
    }

    private static EpgManager.Program program(String title, long start, long end) {
        return new EpgManager.Program(title, start, end);
    }

    private static long minutesAgo(long minutes) {
        return System.currentTimeMillis() - minutes * 60_000L;
    }

    private static TextView timeViewOf(ProgramListAdapter.ProgramViewHolder holder) {
        assertNotNull(holder);
        LinearLayout row = (LinearLayout) holder.itemView;
        return (TextView) row.getChildAt(0);
    }

    @Test
    public void rebindingARecycledRowRestoresTheNormalColour() {
        long now = System.currentTimeMillis();
        List<EpgManager.Program> programs = new ArrayList<>(Arrays.asList(
                program("已结束", minutesAgo(120), minutesAgo(60)),
                program("正在播出", now - 60_000L, now + 60_000L)));

        ProgramListAdapter adapter =
                new ProgramListAdapter(mContext, programs, new RowLabelScroller());
        ViewGroup parent = new LinearLayout(mContext);
        ProgramListAdapter.ProgramViewHolder holder =
                adapter.onCreateViewHolder(parent, 0);

        // Bind the row to the programme on air: it is picked out.
        adapter.onBindViewHolder(holder, 1);
        assertEquals(ProgramListAdapter.ProgramViewHolder.TIME_CURRENT,
                timeViewOf(holder).getCurrentTextColor());

        // The same holder is reused for a finished programme: the highlight must not follow it.
        adapter.onBindViewHolder(holder, 0);
        assertEquals("a recycled row must not stay highlighted",
                ProgramListAdapter.ProgramViewHolder.TIME_NORMAL,
                timeViewOf(holder).getCurrentTextColor());
    }

    @Test
    public void everyRowGetsAColourFromItsOwnProgramme() {
        long now = System.currentTimeMillis();
        List<EpgManager.Program> programs = new ArrayList<>(Arrays.asList(
                program("早间", minutesAgo(300), minutesAgo(240)),
                program("正在播出", now - 60_000L, now + 60_000L),
                program("稍后", now + 3_600_000L, now + 7_200_000L)));

        ProgramListAdapter adapter =
                new ProgramListAdapter(mContext, programs, new RowLabelScroller());
        ViewGroup parent = new LinearLayout(mContext);

        for (int index = 0; index < programs.size(); index++) {
            ProgramListAdapter.ProgramViewHolder holder =
                    adapter.onCreateViewHolder(parent, 0);
            adapter.onBindViewHolder(holder, index);
            int expected = index == 1
                    ? ProgramListAdapter.ProgramViewHolder.TIME_CURRENT
                    : ProgramListAdapter.ProgramViewHolder.TIME_NORMAL;
            assertEquals("row " + index, expected, timeViewOf(holder).getCurrentTextColor());
        }
    }
}
