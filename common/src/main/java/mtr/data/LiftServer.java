package mtr.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.msgpack.value.Value;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LiftServer extends Lift {

	private static final int LIFT_UPDATE_DISTANCE = 64;

	public LiftServer(BlockPos pos, Direction facing) {
		super(pos, facing);
	}

	public LiftServer(Map<String, Value> map) {
		super(map);
	}

	public void tickServer(Level world, Map<Player, Set<LiftServer>> liftsInPlayerRange, Set<LiftServer> liftsToSync) {
		floors.forEach(floor -> world.players().forEach(player -> {
			if (ridingEntities.contains(player.getUUID()) || player.blockPosition().distManhattan(floor) < LIFT_UPDATE_DISTANCE) {
				if (!liftsInPlayerRange.containsKey(player)) {
					liftsInPlayerRange.put(player, new HashSet<>());
				}
				liftsInPlayerRange.get(player).add(this);
			}
		}));

		tick(world, 1);

		final int ridingEntitiesCount = ridingEntities.size();

		double safeWidth = Math.max(liftWidth - 1, 1);
		double safeDepth = Math.max(liftDepth - 1, 1);

		VehicleRidingServer.mountRider(world, ridingEntities, id, 1,
				currentPositionX + liftOffsetX / 2F,
				currentPositionY + liftOffsetY,
				currentPositionZ + liftOffsetZ / 2F,
				safeDepth,
				safeWidth,
				getYaw(), 0, doorValue > 0, true, 0,
				PACKET_UPDATE_LIFT_PASSENGERS, player -> true, player -> {});

		if (liftInstructions.isDirty() || ridingEntitiesCount != ridingEntities.size()) {
			liftsToSync.add(this);
		}
	}
}
