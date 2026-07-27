package cn.nblinks.iot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.nblinks.iot.constants.PROTOCOLS;
import cn.nblinks.iot.dataform.SendMessageForm;
import cn.nblinks.iot.dataform.encode.Snd0x08EncodeForm;
import cn.nblinks.iot.utils.ByteUtil;
import cn.nblinks.teask.gateway.DeviceRegistry;

public class BaseProtocolTest {
    private static final int DEVICE_ID = 10000064;
    private static final String GOLDEN_FRAME_SND_0x08_START = "FE001400010008000000019F1BDDD164030001F40092D3";

    @Test
    public void encodeSnd0x08StartProducesGoldenFrame() {
        Snd0x08EncodeForm cmd = new Snd0x08EncodeForm(
            DEVICE_ID, // deviceId
            null, // mainDeviceId
            DeviceRegistry.get().nextSerial(DEVICE_ID), // serialNo
            3, // slotNo
            0, // chargeType
            GOLDEN_FRAME_SND_0x08_START.substring(14, 32), // flowNo
            500, // money
            null, // chargeScheme
            "", // cardNo
            0, // count
            0 // cardBalance
        );

        SendMessageForm sendForm = new SendMessageForm(cmd);

        String actual = BaseProtocol.enCode(sendForm);

        assertEquals(GOLDEN_FRAME_SND_0x08_START, actual);
    }

    @Test
    public void isPingMsgAcceptsPingFrame() {
        byte[] receivedData = ByteUtil.hexStr2bytes(PROTOCOLS.PING);
        assertTrue(BaseProtocol.isPingMsg(receivedData));
    }

    @Test
    public void isPingMsgRejectsNonPingFrame() {
        String[] nonPingFrames = {"FE00", "FE000000", "FE0001"};

        for (String frame : nonPingFrames) {
            assertFalse(BaseProtocol.isPingMsg(
                ByteUtil.hexStr2bytes(frame)
            ));
        }
    }
}
