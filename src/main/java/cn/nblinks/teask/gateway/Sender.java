package cn.nblinks.teask.gateway;

import cn.nblinks.iot.BaseProtocol;
import cn.nblinks.iot.dataform.BaseMessageForm;
import cn.nblinks.iot.dataform.SendMessageForm;
import cn.nblinks.iot.utils.ByteUtil;
import io.netty.channel.Channel;

/**
 * Turns an SDK encode-form into a framed byte[] and writes it to a channel.
 *
 * The whole point of reusing charger-iot-sdk as-is: BaseProtocol.enCode builds
 * the exact wire frame (SOP/LEN/SERIAL/CTR/CMD/DATA/CRC16) a real charger expects.
 */
public final class Sender {

    private Sender() {}

    /** Send any SDK encode-form (Snd0x02, Snd0x08, ...) to a device channel. */
    public static void send(Channel channel, BaseMessageForm encodeForm) {
        SendMessageForm sendForm = new SendMessageForm(encodeForm);
        String hex = BaseProtocol.enCode(sendForm);
        channel.writeAndFlush(ByteUtil.hexStr2bytes(hex));
    }

    /** Reply to a device heartbeat with the fixed ping frame. */
    public static void sendPing(Channel channel) {
        channel.writeAndFlush(ByteUtil.hexStr2bytes(cn.nblinks.iot.constants.PROTOCOLS.PING));
    }
}
