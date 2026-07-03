package cn.nblinks.teask.gateway;

import cn.nblinks.iot.BaseProtocol;
import cn.nblinks.iot.constants.CMD;
import cn.nblinks.iot.dataform.BaseMessageForm;
import cn.nblinks.iot.dataform.decode.Rec0x01DecodeForm;
import cn.nblinks.iot.dataform.decode.Rec0x0DDecodeForm;
import cn.nblinks.iot.dataform.decode.Rec0x11DecodeForm;
import cn.nblinks.iot.dataform.encode.Snd0x02EncodeForm;
import cn.nblinks.iot.dataform.encode.Snd0x04EncodeForm;
import cn.nblinks.iot.dataform.encode.Snd0x06EncodeForm;
import cn.nblinks.iot.dataform.encode.Snd0x0CEncodeForm;
import cn.nblinks.iot.dataform.encode.Snd0x0EEncodeForm;
import cn.nblinks.iot.dataform.encode.Snd0x10EncodeForm;
import cn.nblinks.iot.dataform.encode.Snd0x12EncodeForm;
import cn.nblinks.iot.dataform.encode.Snd0x1CEncodeForm;
import cn.nblinks.iot.dataform.encode.Snd0x2AEncodeForm;
import com.alibaba.fastjson.JSONObject;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One instance per TCP connection. Decodes each frame with the SDK, auto-acks
 * the reports a real charger expects an ack for, and forwards every decoded
 * message to the TEASK backend.
 *
 * This is the slimmed replacement for the original MainServerHandler: no Redis,
 * no RocketMQ, no protocolApi Feign client — just the SDK codec + an HTTP hop.
 */
public class DeviceHandler extends SimpleChannelInboundHandler<byte[]> {

    private static final Logger log = LoggerFactory.getLogger(DeviceHandler.class);

    /** Bound once the device logs in (cmd 0x01). */
    private Integer deviceId;

    private static final int RESULT_SUCCESS = 0;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, byte[] msg) {
        // Heartbeat: reply with the same fixed ping frame the original server used.
        if (BaseProtocol.isPingMsg(msg)) {
            Sender.sendPing(ctx.channel());
            return;
        }

        BaseMessageForm form;
        try {
            form = BaseProtocol.decode(deviceId, msg);
        } catch (Exception e) {
            log.warn("decode failed: {}", e.getMessage());
            return;
        }

        switch (form.getCmd()) {
            case CMD.REC_0x01_LOGIN:
                handleLogin(ctx, form);
                break;

            case CMD.REC_0x05_TIME:
                Sender.send(ctx.channel(), new Snd0x06EncodeForm(deviceId, null, form.getSerialNo()));
                forward("time", form, null);
                break;

            // Reports real firmware sends that just need an ack + forward. Without the
            // ack the device retries them and can stall its own state machine.
            case CMD.REC_0x03_MODULE_INFO_REPORT:
                Sender.send(ctx.channel(),
                        new Snd0x04EncodeForm(deviceId, null, form.getSerialNo(), RESULT_SUCCESS));
                forward("module_info", form, null);
                break;

            case CMD.REC_0x0B_OFFLINE_START_CHARGE:
                // Local start (coin / free button / offline card). Ack success so the
                // session proceeds; the backend records it via the forward.
                Sender.send(ctx.channel(),
                        new Snd0x0CEncodeForm(deviceId, null, form.getSerialNo(), RESULT_SUCCESS));
                forward("offline_start", form, null);
                break;

            case CMD.REC_0x1B_EVENT_REPORT:
                Sender.send(ctx.channel(), new Snd0x1CEncodeForm(deviceId, null, form.getSerialNo()));
                forward("event", form, null);
                break;

            case CMD.REC_0x29_SELF_CHECKING_RESULT_REPORT:
                Sender.send(ctx.channel(),
                        new Snd0x2AEncodeForm(deviceId, null, form.getSerialNo(), RESULT_SUCCESS));
                forward("self_check_result", form, null);
                break;

            case CMD.REC_0x0D_START_CHARGE_REPORT: {
                Rec0x0DDecodeForm r = new Rec0x0DDecodeForm(form);
                Sender.send(ctx.channel(),
                        new Snd0x0EEncodeForm(deviceId, null, form.getSerialNo(), r.getFlowNo(), r.getSlotNo()));
                JSONObject extra = new JSONObject();
                extra.put("flowNo", r.getFlowNo());
                extra.put("slotNo", r.getSlotNo());
                extra.put("money", r.getMoney());
                extra.put("power", r.getPower());
                forward("charging_started", form, extra);
                break;
            }

            case CMD.REC_0x0F_SLOT_DATA_REPORT:
                Sender.send(ctx.channel(), new Snd0x10EncodeForm(deviceId, null, form.getSerialNo()));
                forward("realtime", form, null);
                break;

            case CMD.REC_0x11_ORDER_END_REPORT: {
                Rec0x11DecodeForm r = new Rec0x11DecodeForm(form);
                Sender.send(ctx.channel(),
                        new Snd0x12EncodeForm(deviceId, null, form.getSerialNo(), r.getFlowNo(), r.getSlotNo()));
                JSONObject extra = new JSONObject();
                extra.put("flowNo", r.getFlowNo());
                extra.put("slotNo", r.getSlotNo());
                extra.put("money", r.getMoney());          // consumed cents — backend deducts this
                extra.put("totalMoney", r.getTotalMoney());
                extra.put("elec", r.getElec());
                extra.put("time", r.getTime());
                extra.put("reason", r.getReason());
                forward("order_complete", form, extra);
                break;
            }

            default:
                forward("raw", form, null);
        }
    }

    private void handleLogin(ChannelHandlerContext ctx, BaseMessageForm form) {
        Rec0x01DecodeForm login = new Rec0x01DecodeForm(form);
        this.deviceId = login.getDeviceId();
        DeviceRegistry.get().register(deviceId, ctx.channel());
        Sender.send(ctx.channel(),
                new Snd0x02EncodeForm(deviceId, null, form.getSerialNo(), RESULT_SUCCESS));
        JSONObject extra = new JSONObject();
        extra.put("slotCount", login.getSlotCount());
        extra.put("softVersion", login.getSoftVersion());
        forward("login", form, extra);
        log.info("device online: {} (slots={})", deviceId, login.getSlotCount());
    }

    private void forward(String event, BaseMessageForm form, JSONObject extra) {
        JSONObject j = new JSONObject();
        j.put("event", event);
        j.put("deviceId", form.getDeviceId());
        j.put("cmd", form.getCmd());
        j.put("serialNo", form.getSerialNo());
        j.put("data", form.getData());
        if (extra != null) j.putAll(extra);
        BackendClient.postReport(j);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        DeviceRegistry.get().removeByChannel(ctx.channel());
        if (deviceId != null) log.info("device offline: {}", deviceId);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // IdleStateHandler fired: no bytes from the device past the ping window.
        // Cellular links drop silently — close so the registry reflects reality and
        // the device reconnects + re-logs-in.
        if (evt instanceof IdleStateEvent) {
            log.info("idle timeout, closing channel (device {})", deviceId);
            ctx.close();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("channel error ({}): {}", deviceId, cause.getMessage());
        ctx.close();
    }
}
