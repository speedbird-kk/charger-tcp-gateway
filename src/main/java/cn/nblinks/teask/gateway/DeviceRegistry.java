package cn.nblinks.teask.gateway;

import io.netty.channel.Channel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory replacement for the original Redis-backed StoreServer/ChannelContext.
 * Maps a logged-in deviceId to its live TCP channel, and hands out per-device
 * serial numbers for platform-initiated commands.
 *
 * For local single-node development this is all we need; Redis was only there to
 * share connection state across a horizontally-scaled cluster.
 */
public final class DeviceRegistry {

    private static final DeviceRegistry INSTANCE = new DeviceRegistry();

    public static DeviceRegistry get() {
        return INSTANCE;
    }

    private final Map<Integer, Channel> devices = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicInteger> serials = new ConcurrentHashMap<>();

    private DeviceRegistry() {}

    public void register(Integer deviceId, Channel channel) {
        devices.put(deviceId, channel);
        serials.putIfAbsent(deviceId, new AtomicInteger(0));
    }

    public Channel channelOf(Integer deviceId) {
        return devices.get(deviceId);
    }

    public boolean isOnline(Integer deviceId) {
        Channel c = devices.get(deviceId);
        return c != null && c.isActive();
    }

    /** Next serial number for a platform-pushed command (wraps at 0xFFFF). */
    public int nextSerial(Integer deviceId) {
        AtomicInteger s = serials.computeIfAbsent(deviceId, k -> new AtomicInteger(0));
        return s.updateAndGet(v -> (v + 1) & 0xFFFF);
    }

    /** Remove whatever device is bound to a channel that just went away. */
    public void removeByChannel(Channel channel) {
        devices.entrySet().removeIf(e -> e.getValue() == channel);
    }

    public java.util.Set<Integer> onlineDeviceIds() {
        return devices.keySet();
    }
}
