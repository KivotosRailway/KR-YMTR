package mtr.mixin;

import mtr.render.RenderTrains;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import top.mcmtr.data.RigidCatenary;

@Pseudo
@Mixin(value = {RigidCatenary.class}, remap = false)
public class MSDRigidCatenaryMixin {

	@ModifyArg(method = {"render"}, at = @At(value = "INVOKE",
			target = "Ltop/mcmtr/data/RigidCatenary;renderSegment(Ljava/util/List;Ltop/mcmtr/data/RigidCatenary$RenderRigidCatenary;)V",
			ordinal = 0, remap = false), index = 1, remap = false)
	private RigidCatenary.RenderRigidCatenary wrapRenderCallback0(RigidCatenary.RenderRigidCatenary callback) {
		return wrapRigidCallback(callback);
	}

	@ModifyArg(method = {"render"}, at = @At(value = "INVOKE",
			target = "Ltop/mcmtr/data/RigidCatenary;renderSegment(Ljava/util/List;Ltop/mcmtr/data/RigidCatenary$RenderRigidCatenary;)V",
			ordinal = 1, remap = false), index = 1, remap = false)
	private RigidCatenary.RenderRigidCatenary wrapRenderCallback1(RigidCatenary.RenderRigidCatenary callback) {
		return wrapRigidCallback(callback);
	}

	private static RigidCatenary.RenderRigidCatenary wrapRigidCallback(RigidCatenary.RenderRigidCatenary callback) {
		final Vec3 cameraPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
		return (x1, z1, x2, z2, x3, z3, x4, z4,
		        xs1, zs1, xs2, zs2, xs3, zs3, xs4, zs4,
		        y1, y2) -> {
			RenderTrains.MSD_CAMERA_RELATIVE.set(true);
			try {
				callback.renderRigidCatenary(
						x1 - cameraPos.x, z1 - cameraPos.z,
						x2 - cameraPos.x, z2 - cameraPos.z,
						x3 - cameraPos.x, z3 - cameraPos.z,
						x4 - cameraPos.x, z4 - cameraPos.z,
						xs1 - cameraPos.x, zs1 - cameraPos.z,
						xs2 - cameraPos.x, zs2 - cameraPos.z,
						xs3 - cameraPos.x, zs3 - cameraPos.z,
						xs4 - cameraPos.x, zs4 - cameraPos.z,
						y1 - cameraPos.y, y2 - cameraPos.y);
			} finally {
				RenderTrains.MSD_CAMERA_RELATIVE.set(false);
			}
		};
	}
}