package cn.nblinks.iot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.nblinks.iot.constants.CMD;
import cn.nblinks.iot.constants.PROTOCOLS;
import cn.nblinks.iot.dataform.BaseMessageForm;
import cn.nblinks.iot.dataform.SendMessageForm;
import cn.nblinks.iot.dataform.encode.Snd0x08EncodeForm;
import cn.nblinks.iot.utils.ByteUtil;
import cn.nblinks.teask.gateway.DeviceRegistry;

public class BaseProtocolTest {
    private static final int DEVICE_ID = 10000064;
    private static final String GOLDEN_FRAME_REC_0x01_LOGIN 
        = "FE002E0001000100100000640A0100544541534b2d53494d0000000000000000000000000000000000000000000DAC8090";
    private static final String GOLDEN_FRAME_REC_0x0D_START_REP
        = "FE001A0002000D000000019F1BDDD164030001F46553F10000000088BD";
    private static final String GOLDEN_FRAME_SND_0x08_START = "FE001400010008000000019F1BDDD164030001F40092D3";

    @Test
    public void encodeRec0x01LoginProducesGoldenFrame() {
        StringBuilder softwareVersion = new StringBuilder(
            ByteUtil.convertStringToHex("TEASK-SIM")
        );

        while (softwareVersion.length() < 52) {
            softwareVersion.append("0");
        }
    
        StringBuilder data = new StringBuilder();
        data.append(String.format("%010d", DEVICE_ID)); // Device id (decimals)
        data.append(ByteUtil.decimal2fitHex(10, 2)); // Slot count
        data.append(ByteUtil.decimal2fitHex(1, 2)); // Transmission version
        data.append(ByteUtil.decimal2fitHex(0, 2)); // Device type
        data.append(softwareVersion.toString()); // Software version
        data.append("00000000"); // Function mask
        data.append(ByteUtil.decimal2fitHex(3500, 4)); // Slot max power (W)

        SendMessageForm sendForm = new SendMessageForm();

        sendForm.setDeviceId(DEVICE_ID);
        sendForm.setSubDeviceId(null);
        sendForm.setSerialNo(1);
        sendForm.setCmd(CMD.REC_0x01_LOGIN);
        sendForm.setData(data.toString());

        String actual = BaseProtocol.enCode(sendForm);

        assertEquals(GOLDEN_FRAME_REC_0x01_LOGIN, actual);
    }

    @Test
    public void encodeRec0x0DStartRepProducesGoldenFrame() {
        StringBuilder data = new StringBuilder();
        data.append(GOLDEN_FRAME_SND_0x08_START.substring(14, 32)); // Flow number
        data.append(ByteUtil.decimal2fitHex(3, 2)); // Slot number
        data.append(ByteUtil.decimal2fitHex(0, 2)); // Charge type
        data.append(ByteUtil.decimal2fitHex(500, 4)); // Money
        data.append(GOLDEN_FRAME_REC_0x0D_START_REP.substring(40, 48)); // Start time
        data.append(ByteUtil.decimal2fitHex(0, 4));
        data.append(ByteUtil.decimal2fitHex(0, 2));

        SendMessageForm sendForm = new SendMessageForm();

        sendForm.setDeviceId(DEVICE_ID);
        sendForm.setSubDeviceId(null);
        sendForm.setSerialNo(2);
        sendForm.setCmd(CMD.REC_0x0D_START_CHARGE_REPORT);
        sendForm.setData(data.toString());

        String actual = BaseProtocol.enCode(sendForm);

        assertEquals(GOLDEN_FRAME_REC_0x0D_START_REP, actual);
    }

    @Test
    public void encodeSnd0x08StartProducesGoldenFrame() {
        Snd0x08EncodeForm cmd = new Snd0x08EncodeForm(
            DEVICE_ID, // deviceId
            null, // mainDeviceId
            1, // serialNo
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
    public void roundTripSnd0x08StartPreservesBaseMessageFormFields() throws Exception {
        Snd0x08EncodeForm cmd = new Snd0x08EncodeForm(
            DEVICE_ID, // deviceId
            null, // mainDeviceId
            1, // serialNo
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

        BaseMessageForm expected = new BaseMessageForm(
            cmd.getDeviceId(),
            cmd.getSerialNo(),
            cmd.getMainDeviceId(),
            cmd.getCmd(),
            cmd.getData()
        );


        BaseMessageForm actual = BaseProtocol.decode(
            DEVICE_ID,
            ByteUtil.hexStr2bytes(BaseProtocol.enCode(sendForm))
        );

        assertEquals(expected, actual);
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
