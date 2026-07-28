package com.cabletv.player.config;

import com.cabletv.player.model.Channel;
import com.cabletv.player.model.ChannelGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Groups the channel list for navigation and remembers which channels are favourites.
 *
 * The rest of the app identifies a channel by its position in the flat playlist, so every index
 * this class accepts or returns is such a flat one; a group only decides which of those indices the
 * channel keys can reach.
 */
public class ChannelNavigator {

    /** The favourites group is always first, so its position does not move as playlists change. */
    public static final int FAVORITES_GROUP = 0;
    /** Every channel in the playlist, for viewers who would rather not be fenced into a group. */
    public static final int ALL_GROUP = 1;
    /** The playlist's own groups start after the two built-in ones. */
    private static final int FIRST_PLAYLIST_GROUP = 2;

    /** A navigable group: the playlist's own groups, with the built-in ones in front of them. */
    public static class Group {
        /** Playlist group name; empty for the built-in groups, whose labels belong to the UI. */
        public final String name;
        public final boolean favorites;
        public final boolean all;
        /** Positions in the flat channel list, in playlist order. */
        public final List<Integer> channels;

        Group(String name, boolean favorites, boolean all, List<Integer> channels) {
            this.name = name;
            this.favorites = favorites;
            this.all = all;
            this.channels = Collections.unmodifiableList(channels);
        }
    }

    private final Set<String> mFavorites = new LinkedHashSet<>(AppConfig.getFavoriteChannels());
    private List<Channel> mFlatChannels = Collections.emptyList();
    private List<Group> mGroups = new ArrayList<>();
    /** The group the channel keys walk while watching full screen; everything, until told otherwise. */
    private int mCurrentGroup = ALL_GROUP;

    public ChannelNavigator() {
        setChannels(null, null);
    }

    public void setChannels(List<ChannelGroup> groups, List<Channel> flatChannels) {
        mFlatChannels = flatChannels != null ? flatChannels : Collections.<Channel>emptyList();

        List<Group> built = new ArrayList<>();
        built.add(buildFavoritesGroup());
        built.add(buildAllGroup());
        int flatIndex = 0;
        if (groups != null) {
            for (ChannelGroup group : groups) {
                List<Integer> indices = new ArrayList<>();
                if (group.channels != null) {
                    for (int i = 0; i < group.channels.size(); i++) {
                        // The flat list is the groups concatenated in order, so counting along
                        // them yields the same indices the repository handed out.
                        indices.add(flatIndex++);
                    }
                }
                if (!indices.isEmpty()) {
                    built.add(new Group(group.name != null ? group.name : "", false, false, indices));
                }
            }
        }
        mGroups = built;
        if (mCurrentGroup >= mGroups.size()) {
            mCurrentGroup = ALL_GROUP;
        }
    }

    public int groupCount() {
        return mGroups.size();
    }

    public Group group(int groupIndex) {
        return groupIndex >= 0 && groupIndex < mGroups.size() ? mGroups.get(groupIndex) : null;
    }

    public int currentGroupIndex() {
        return mCurrentGroup;
    }

    public void setCurrentGroupIndex(int groupIndex) {
        if (groupIndex >= 0 && groupIndex < mGroups.size()) {
            mCurrentGroup = groupIndex;
        }
    }

    /**
     * @return the playlist group holding this channel, never a built-in group: a channel is always
     *         somewhere in the playlist, but only sometimes a favourite.
     */
    public int playlistGroupOf(int channelIndex) {
        for (int i = FIRST_PLAYLIST_GROUP; i < mGroups.size(); i++) {
            if (mGroups.get(i).channels.contains(channelIndex)) {
                return i;
            }
        }
        return ALL_GROUP;
    }

    /**
     * @return the group the channel keys should walk once this channel is playing: the current one
     *         when it holds the channel — so tuning by number inside "All" stays in "All" — and
     *         otherwise the playlist group the channel came from.
     */
    public int homeGroupFor(int channelIndex) {
        return positionOf(mCurrentGroup, channelIndex) >= 0 ? mCurrentGroup : playlistGroupOf(channelIndex);
    }

    public List<Channel> channelsOf(int groupIndex) {
        List<Channel> channels = new ArrayList<>();
        Group group = group(groupIndex);
        if (group != null) {
            for (int index : group.channels) {
                if (index >= 0 && index < mFlatChannels.size()) {
                    channels.add(mFlatChannels.get(index));
                }
            }
        }
        return channels;
    }

    /** @return the flat index of the channel shown at that row of the group, or -1. */
    public int channelIndexAt(int groupIndex, int position) {
        Group group = group(groupIndex);
        if (group == null || position < 0 || position >= group.channels.size()) {
            return -1;
        }
        return group.channels.get(position);
    }

    /** @return which row of the group shows this channel, or -1 when it is not in the group. */
    public int positionOf(int groupIndex, int channelIndex) {
        Group group = group(groupIndex);
        return group != null ? group.channels.indexOf(channelIndex) : -1;
    }

    /**
     * Walks one group, wrapping at its ends, so the channel keys stay inside the group the viewer
     * chose instead of wandering into an unrelated part of the playlist.
     *
     * @return the flat index to tune to, or -1 when the group is empty
     */
    public int step(int groupIndex, int channelIndex, int delta) {
        Group group = group(groupIndex);
        if (group == null || group.channels.isEmpty()) {
            return -1;
        }
        int count = group.channels.size();
        int position = group.channels.indexOf(channelIndex);
        if (position < 0) {
            // Tuned outside the group (a channel number was typed, say): enter at the end the
            // viewer is heading towards.
            return group.channels.get(delta >= 0 ? 0 : count - 1);
        }
        return group.channels.get(((position + delta) % count + count) % count);
    }

    public boolean isFavorite(Channel channel) {
        return channel != null && channel.name != null && mFavorites.contains(channel.name);
    }

    /**
     * Adds or removes a favourite and saves the result immediately, so a favourite survives the app
     * being killed right after it was marked.
     *
     * @return true when the channel is a favourite afterwards
     */
    public boolean toggleFavorite(Channel channel) {
        if (channel == null || channel.name == null || channel.name.isEmpty()) {
            return false;
        }
        boolean nowFavorite = !mFavorites.remove(channel.name);
        if (nowFavorite) {
            mFavorites.add(channel.name);
        }
        AppConfig.setFavoriteChannels(new ArrayList<>(mFavorites));
        if (mGroups.isEmpty()) {
            mGroups.add(buildFavoritesGroup());
        } else {
            mGroups.set(FAVORITES_GROUP, buildFavoritesGroup());
        }
        return nowFavorite;
    }

    private Group buildFavoritesGroup() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < mFlatChannels.size(); i++) {
            if (isFavorite(mFlatChannels.get(i))) {
                indices.add(i);
            }
        }
        return new Group("", true, false, indices);
    }

    private Group buildAllGroup() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < mFlatChannels.size(); i++) {
            indices.add(i);
        }
        return new Group("", false, true, indices);
    }
}
