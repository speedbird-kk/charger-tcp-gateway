package cn.nblinks.iot.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Crc16UtilTest {
    private static final String GOLDEN_FRAME_SND_0x08_START = "FE001400010008000000019F1BDDD164030001F40092D3";

    @Test
    public void checkCrcAcceptsGoldenFrameAndRejectsCorruptedGoldenFrame() {
        byte[] frame = ByteUtil.hexStr2bytes(GOLDEN_FRAME_SND_0x08_START);
        byte[] corruptedFrame = frame.clone();
        
        /*
        Corrupt the 17th byte, in this case the slot number,
        by taking its XOR with 0b00000001 which leaves first
        7 bits unchanged and flips the last bit of the byte.
        */
        corruptedFrame[17] ^= 0x01; 

        assertTrue(Crc16Util.checkCrc(frame));
        assertFalse(Crc16Util.checkCrc(corruptedFrame));
    }
}
