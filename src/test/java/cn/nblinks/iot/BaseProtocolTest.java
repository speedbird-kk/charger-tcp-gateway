package cn.nblinks.iot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.nblinks.iot.constants.CMD;
import cn.nblinks.iot.constants.PROTOCOLS;
import cn.nblinks.iot.dataform.BaseMessageForm;
import cn.nblinks.iot.dataform.SendMessageForm;
import cn.nblinks.iot.dataform.encode.Snd0x02EncodeForm;
import cn.nblinks.iot.dataform.encode.Snd0x08EncodeForm;
import cn.nblinks.iot.dataform.encode.Snd0x14EncodeForm;
import cn.nblinks.iot.utils.ByteUtil;

public class BaseProtocolTest {
    private static final int DEVICE_ID = 10000064;
    private static final String GOLDEN_FRAME_REC_0x01_LOGIN 
        = "FE002E0001000100100000640A0100544541534b2d53494d0000000000000000000000000000000000000000000DAC8090";

    private static final String GOLDEN_FRAME_REC_0x0D_START_REP
        = "FE001A0002000D000000019F1BDDD164030001F46553F10000000088BD";

    private static final String GOLDEN_FRAME_REC_0x11_ORDER_END
        = "FE002800030011000000019F1BDDD16403000000006553F1006553F13C0001003201F401F4012C0000A671";

    private static final String GOLDEN_FRAME_SND_0X02_LOGIN_REP
        = "FE000B00010002006A4493248609";

    private static final String GOLDEN_FRAME_SND_0x08_START
        = "FE001400010008000000019F1BDDD164030001F40092D3";

    private static final String GOLDEN_FRAME_SND_0X14_STOP
        = "FE00070002001403540E";

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
    public void encodeRec0x11OrderEndProducesGoldenFrame() {
        StringBuilder data = new StringBuilder();
        data.append(GOLDEN_FRAME_REC_0x11_ORDER_END.substring(14, 32)); // Flow number
        data.append(ByteUtil.decimal2fitHex(3, 2)); // Slot number
        data.append(ByteUtil.decimal2fitHex(0, 2)); // Charge type
        data.append(ByteUtil.decimal2fitHex(0, 2)); // Reason
        data.append(ByteUtil.decimal2fitHex(0, 4)); // Error code
        data.append("6553F100"); // Start time
        data.append("6553F13C"); // End time
        data.append(ByteUtil.decimal2fitHex(1, 4)); // Time
        data.append(ByteUtil.decimal2fitHex(50, 4)); // Electricity
        data.append(ByteUtil.decimal2fitHex(500, 4)); // Total money
        data.append(ByteUtil.decimal2fitHex(500, 4)); // Money consumed
        data.append(ByteUtil.decimal2fitHex(300, 4)); // Max power
        data.append(ByteUtil.decimal2fitHex(0, 2)); // Extra data
        data.append(ByteUtil.decimal2fitHex(0, 2)); // Charge scheme

        SendMessageForm sendForm = new SendMessageForm();

        sendForm.setDeviceId(DEVICE_ID);
        sendForm.setSubDeviceId(null);
        sendForm.setSerialNo(3);
        sendForm.setCmd(CMD.REC_0x11_ORDER_END_REPORT);
        sendForm.setData(data.toString());

        String actual = BaseProtocol.enCode(sendForm);

        assertEquals(GOLDEN_FRAME_REC_0x11_ORDER_END, actual);
    }

    @Test
    public void encodeSnd0x02LoginRepProducesGoldenFrame() {
        Snd0x02EncodeForm cmd = new Snd0x02EncodeForm(
            DEVICE_ID, // deviceId
            null, // mainDeviceId
            1, // serialNo
            0 // result
        );

        /*
        Timestamp must be mutated according to the golden frame
        because the constructor initialises the timestamp to the
        System.currentTimeMillis() / 1000.
        */
        cmd.setTimestamp(1782879012);

        SendMessageForm sendForm = new SendMessageForm(cmd);

        String actual = BaseProtocol.enCode(sendForm);

        assertEquals(GOLDEN_FRAME_SND_0X02_LOGIN_REP, actual);
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
    public void encodeSnd0x14StopProducesGoldenFrame() {
        Snd0x14EncodeForm cmd = new Snd0x14EncodeForm(
            DEVICE_ID, // deviceId
            null, // mainDeviceId
            2, // serialNo
            3 // slotNo
        );

        SendMessageForm sendForm = new SendMessageForm(cmd);

        String actual = BaseProtocol.enCode(sendForm);

        assertEquals(GOLDEN_FRAME_SND_0X14_STOP, actual);
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
