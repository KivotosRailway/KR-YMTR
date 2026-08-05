package mtr.item;

import mtr.CreativeModeTabs;
import mtr.block.BlockNode;
import mtr.packet.PacketUpdateRailData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class ItemRailDataEditor extends ItemWithCreativeTabBase {

	public ItemRailDataEditor() {
		super(CreativeModeTabs.CORE, properties -> properties.stacksTo(1));
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		final Level level = context.getLevel();
		if (!(level.getBlockState(context.getClickedPos()).getBlock() instanceof BlockNode)) {
			return super.useOn(context);
		}
		if (!level.isClientSide && context.getPlayer() instanceof ServerPlayer) {
			PacketUpdateRailData.openRailDataEditorS2C((ServerPlayer) context.getPlayer(), context.getClickedPos());
		}
		return InteractionResult.SUCCESS;
	}
}
