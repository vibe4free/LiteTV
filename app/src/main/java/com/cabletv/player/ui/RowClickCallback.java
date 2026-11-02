package com.cabletv.player.ui;

/**
 * Told when a row in any of the three columns is tapped.
 *
 * <p>A top-level type because the three adapters live in their own files now and all of them
 * report clicks through it.
 */
public interface RowClickCallback {
    /** @param position the row's position in the adapter that reported it */
    void onRowClick(int position);
}
