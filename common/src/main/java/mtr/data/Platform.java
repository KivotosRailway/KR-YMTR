package mtr.data;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.msgpack.value.Value;

import java.util.Map;
import java.util.function.Consumer;

public final class Platform extends SavedRailBase {

	private static final String KEY_DWELL_TIME = "dwell_time";
	private static final String KEY_ADC_TIME = "adc_time";
	private static final String KEY_PSD_DISPLAY_MODE = "psd_display_mode";

	public Platform(long id, TransportMode transportMode, BlockPos pos1, BlockPos pos2) {
		super(id, transportMode, pos1, pos2);
	}

	public Platform(TransportMode transportMode, BlockPos pos1, BlockPos pos2) {
		super(transportMode, pos1, pos2);
	}

	public Platform(Map<String, Value> map) {
		super(map);
	}

	@Deprecated
	public Platform(CompoundTag compoundTag) {
		super(compoundTag);
	}

	public Platform(FriendlyByteBuf packet) {
		super(packet);
	}

	@Override
	public void update(String key, FriendlyByteBuf packet) {
		if (KEY_DWELL_TIME.equals(key)) {
			name = packet.readUtf(PACKET_STRING_READ_LENGTH);
			color = packet.readInt();
			dwellTime = packet.readInt();
			dwellTime = transportMode.continuousMovement ? 1 : dwellTime;
		} else if (KEY_ADC_TIME.equals(key)) {
			adcTime = packet.readInt();
			adcTime = transportMode.continuousMovement ? 0 : adcTime;
		} else if (KEY_STOP_WITHOUT_OPENING_DOORS.equals(key)) {
			stopWithoutOpeningDoors = packet.readBoolean();
		} else if (KEY_PSD_DISPLAY_MODE.equals(key)) {
			psdDisplayMode = packet.readInt();
		} else {
			super.update(key, packet);
		}
	}

	public void setDwellTime(int newDwellTime, Consumer<FriendlyByteBuf> sendPacket) {
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_DWELL_TIME);
		packet.writeUtf(name);
		packet.writeInt(color);
		writeDwellTimePacket(packet, newDwellTime);
		sendPacket.accept(packet);
	}

	public void setAdcTime(int newAdcTime, Consumer<FriendlyByteBuf> sendPacket) {
		if (transportMode.continuousMovement) {
			adcTime = 0;
		} else if (newAdcTime < 0 || newAdcTime > MAX_ADC_TIME) {
			adcTime = 0;
		} else {
			adcTime = newAdcTime;
		}
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_ADC_TIME);
		packet.writeInt(adcTime);
		sendPacket.accept(packet);
	}

	public void setStopWithoutOpeningDoors(boolean stopWithoutOpeningDoors, Consumer<FriendlyByteBuf> sendPacket) {
		this.stopWithoutOpeningDoors = stopWithoutOpeningDoors;
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_STOP_WITHOUT_OPENING_DOORS);
		packet.writeBoolean(stopWithoutOpeningDoors);
		sendPacket.accept(packet);
	}

	public void setPsdDisplayMode(int newPsdDisplayMode, Consumer<FriendlyByteBuf> sendPacket) {
		psdDisplayMode = newPsdDisplayMode;
		final FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
		packet.writeLong(id);
		packet.writeUtf(transportMode.toString());
		packet.writeUtf(KEY_PSD_DISPLAY_MODE);
		packet.writeInt(psdDisplayMode);
		sendPacket.accept(packet);
	}
}