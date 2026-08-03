package mtr.item;

import mtr.CreativeModeTabs;
import mtr.block.BlockNode;
import mtr.client.ClientData;
import mtr.data.Rail;
import mtr.data.RailType;
import mtr.mappings.Text;
import mtr.screen.RailDataEditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ItemRailDataEditor extends ItemWithCreativeTabBase {

	public ItemRailDataEditor() {
		super(CreativeModeTabs.CORE, properties -> properties.stacksTo(1));
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		final Level level = context.getLevel();
		final BlockPos pos = context.getClickedPos();
		if (!(level.getBlockState(pos).getBlock() instanceof BlockNode)) {
			return super.useOn(context);
		}
		if (level.isClientSide) {
			final List<RailInfo> rails = collectRailsAtNode(pos);
			if (rails.isEmpty()) {
				if (context.getPlayer() != null) {
					context.getPlayer().displayClientMessage(Text.translatable("gui.mtr.rail_data_editor.no_rail"), true);
				}
			} else {
				Minecraft.getInstance().setScreen(new RailDataEditorScreen(rails));
			}
		}
		return InteractionResult.SUCCESS;
	}

	public static List<RailInfo> collectRailsAtNode(BlockPos pos) {
		final Map<Long, RailInfo> result = new LinkedHashMap<>();
		final Map<BlockPos, Rail> fromPos = ClientData.RAILS.get(pos);
		if (fromPos != null) {
			for (Map.Entry<BlockPos, Rail> entry : fromPos.entrySet()) {
				final Rail rail = entry.getValue();
				if (rail.railType == RailType.NONE) {
					continue;
				}
				result.put(railKey(pos, entry.getKey()), new RailInfo(rail, pos, entry.getKey()));
			}
		}
		for (Map.Entry<BlockPos, Map<BlockPos, Rail>> outer : ClientData.RAILS.entrySet()) {
			final Rail rail = outer.getValue().get(pos);
			if (rail != null && rail.railType != RailType.NONE) {
				result.put(railKey(outer.getKey(), pos), new RailInfo(rail, outer.getKey(), pos));
			}
		}
		return new ArrayList<>(result.values());
	}

	private static long railKey(BlockPos posStart, BlockPos posEnd) {
		final long start = posStart.asLong();
		final long end = posEnd.asLong();
		return start < end ? start * 31L + end : end * 31L + start;
	}

	public static class RailInfo {
		public final Rail rail;
		public final BlockPos posStart;
		public final BlockPos posEnd;

		public RailInfo(Rail rail, BlockPos posStart, BlockPos posEnd) {
			this.rail = rail;
			this.posStart = posStart;
			this.posEnd = posEnd;
		}
	}
}
