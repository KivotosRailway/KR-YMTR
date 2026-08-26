package mtr.mixin;

import mtr.render.RenderTrains;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import top.mcmtr.data.TransCatenary;

@Pseudo
@Mixin(value = {TransCatenary.class}, remap = false)
public class MSDTransCatenaryMixin {

	@ModifyArg(method = {"render"}, at = @At(value = "INVOKE",
			target = "Ltop/mcmtr/data/TransCatenary;renderSegment(Ltop/mcmtr/data/TransCatenary$RenderTransCatenary;)V",
			remap = false), index = 0, remap = false)
	private TransCatenary.RenderTransCatenary wrapRenderCallback(TransCatenary.RenderTransCatenary callback) {
		final Vec3 cameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
		return (x1, y1, z1, x2, y2, z2, count, i, base, sinX, sinZ, increment) -> {
			RenderTrains.MSD_CAMERA_RELATIVE.set(true);
			try {
				callback.renderTransCatenary(
						x1 - cameraPos.x, y1 - cameraPos.y, z1 - cameraPos.z,
						x2 - cameraPos.x, y2 - cameraPos.y, z2 - cameraPos.z,
						count, i, base, sinX, sinZ, increment);
			} finally {
				RenderTrains.MSD_CAMERA_RELATIVE.set(false);
			}
		};
	}
}