package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The group row used to hold "全部频道 (155)" in a single TextView, and the narrow column made the
 * count wrap onto a second line. The name and the count are separate views now, and neither of them
 * may wrap.
 */
@RunWith(RobolectricTestRunner.class)
public class GroupRowLayoutTest {

    private TextView nameOf(LinearLayout row) {
        return (TextView) row.getChildAt(0);
    }

    private TextView countOf(LinearLayout row) {
        return (TextView) row.getChildAt(1);
    }

    private LinearLayout buildRow(Context context) {
        RecyclerView recycler = new RecyclerView(context);
        ChannelListComponent.GroupListAdapter adapter =
                new ChannelListComponent.GroupListAdapter(context, new com.cabletv.player.config.ChannelNavigator());
        return (LinearLayout) adapter.onCreateViewHolder(recycler, 0).itemView;
    }

    @Test
    public void nameAndCountAreSeparateViews() {
        LinearLayout row = buildRow(RuntimeEnvironment.getApplication());
        assertEquals(2, row.getChildCount());
        assertTrue("the name must not wrap", nameOf(row).getMaxLines() == 1);
        assertTrue("the count must not wrap", countOf(row).getMaxLines() == 1);
    }

    @Test
    public void theCountTakesOnlyTheRoomItNeeds() {
        LinearLayout row = buildRow(RuntimeEnvironment.getApplication());
        LinearLayout.LayoutParams countParams = (LinearLayout.LayoutParams) countOf(row).getLayoutParams();
        assertEquals("the count is sized by its content, not a fixed column",
                LinearLayout.LayoutParams.WRAP_CONTENT, countParams.width);
        // The name takes the rest of the row.
        LinearLayout.LayoutParams nameParams = (LinearLayout.LayoutParams) nameOf(row).getLayoutParams();
        assertEquals(1f, nameParams.weight, 0.01f);
    }

    @Test
    public void theRowKeepsItsHeight() {
        LinearLayout row = buildRow(RuntimeEnvironment.getApplication());
        RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) row.getLayoutParams();
        assertEquals("the row height must not change", 
                Math.round(ChannelListComponent.GROUP_ROW_HEIGHT_DP
                        * RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density),
                params.height);
    }
}
