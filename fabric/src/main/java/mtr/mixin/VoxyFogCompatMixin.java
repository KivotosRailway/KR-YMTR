package mtr.mixin;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * MTR 与 Voxy 兼容修复（保险 1）：保证 Voxy 把 LOD 深度写回主深度缓冲。
 *
 * Voxy 的 NormalRenderPipeline.finish() 以 fogCoversAllRendering（fogEnd < 渲染距离）
 * 决定是否把 LOD 深度写回主缓冲。不开光影时 Voxy 的 MixinFogRenderer 把雾强制改到
 * 999999999 保证该判断为 false；开光影（Iris 接管雾）时该判断可能误判为 true，
 * 深度回写被跳过 → MTR 列车/轨道/电梯（LEQUAL 深度测试）在主深度缓冲中找不到 LOD 区域
 * 的地形深度 → 透视/穿墙。
 *
 * 修复：VoxyRenderSystem.setCapturedFog() 每帧被 MixinFogRenderer 调用，本 mixin 在其
 * TAIL 用反射把 capturedFogEnd 字段抬高到不小于渲染距离，使 finish() 的 fog 判断恒为
 * false，Voxy 原生的深度回写照常执行（仅写深度，对已有深度零副作用）。
 *
 * 注意：本 mixin 在独立的 mtr.voxy.mixins.json（required=false）中注册；Voxy 未安装或
 * 版本不匹配时静默跳过，不会影响游戏启动。生效与否以日志 "[MTR-Voxy] ... active" 为准。
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public abstract class VoxyFogCompatMixin {

	private static final Logger LOGGER = LogManager.getLogger("MTR");
	private static boolean mtr$logged;

	@Inject(method = "setCapturedFog", at = @At("TAIL"), remap = false)
	private void mtr$ensureLodDepthBlitNotSkipped(CallbackInfo ci) {
		try {
			final Field capturedFogEndField = getClass().getDeclaredField("capturedFogEnd");
			capturedFogEndField.setAccessible(true);
			final float fogEnd = capturedFogEndField.getFloat(this);
			final float renderDistance = Minecraft.getInstance().gameRenderer.getRenderDistance();
			if (fogEnd < renderDistance) {
				// 钳到 Voxy 设计雾距量级（32768），不能压缩到渲染距离附近，否则半透明 LOD 被雾覆盖
				capturedFogEndField.setFloat(this, 32768.0f);
				if (!mtr$logged) {
					mtr$logged = true;
					LOGGER.info("[MTR-Voxy] VoxyFogCompatMixin active: capturedFogEnd {} -> 32768.0 (LOD depth write guaranteed)", fogEnd);
				}
			} else if (!mtr$logged) {
				mtr$logged = true;
				LOGGER.info("[MTR-Voxy] VoxyFogCompatMixin active: capturedFogEnd {} already >= renderDistance {}", fogEnd, renderDistance);
			}
		} catch (Throwable t) {
			if (!mtr$logged) {
				mtr$logged = true;
				LOGGER.error("[MTR-Voxy] VoxyFogCompatMixin failed (version mismatch?): {}", t.toString(), t);
			}
		}
	}
}
