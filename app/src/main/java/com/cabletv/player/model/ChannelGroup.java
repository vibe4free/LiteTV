package com.cabletv.player.model;

import java.util.ArrayList;
import java.util.List;

public class ChannelGroup {
    public String name;
    public List<Channel> channels = new ArrayList<>();

    public ChannelGroup() {
    }

    public ChannelGroup(String name) {
        this.name = name;
    }

    public void addChannel(Channel channel) {
        channels.add(channel);
        channel.group = this.name;
    }

    @Override
    public String toString() {
        return "ChannelGroup{" +
                "name='" + name + '\'' +
                ", channels=" + channels.size() +
                '}';
    }
}
