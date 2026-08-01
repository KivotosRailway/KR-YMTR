package mtr.client;

import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * MTR 与 Voxy 模组的深度兼容修复（运行期反射实现，编译期零依赖 Voxy）。
 *
 * 背景：MTR 的列车/轨道/电梯不是实体，在 renderLevel 末尾通过自定义渲染 flush 绘制，
 * 深度测试为 LEQUAL，依赖主深度缓冲中存在 Voxy LOD 区域的远处地形深度。
 * Voxy 把 LOD 深度写回主缓冲是有条件的（NormalRenderPipeline.finish 依赖 fog 判断，
 * IrisVoxyRenderPipeline.finish 依赖光影配置），开/关光影时条件都可能不满足，
 * 主深度缓冲缺失 LOD 深度，导致 MTR 远处内容透视/穿墙（在地面能看到地下的地铁/电梯）。
 *
 * 本类在 MTR 的 RenderTrains.render 入口中、绘制之前调用，强制把 Voxy 的 LOD 深度
 * 写回「当前绑定的帧缓冲」——与 MTR flush 的目标一定是同一个缓冲，不依赖 Voxy 内部
 * 条件，也不依赖「Voxy 写回的缓冲 == MTR 的缓冲」的假设。
 *
 * 深度回写 shader（Voxy 自带）对深度为 0/1 的像素 discard，不覆盖已有深度，幂等且安全；
 * 颜色掩码关闭，不修改任何颜色输出。Voxy 未安装时本类自动静默跳过（仅一次布尔判断）。
 */
public final class VoxyDepthCompat {

	private static final Logger LOGGER = LogManager.getLogger("MTR");

	private static boolean initialized;
	private static boolean available;
	private static boolean loggedError;
	private static boolean loggedActive;

	// 反射缓存
	private static Object voxyRenderSystem; // VoxyRenderSystem 实例（从 levelRenderer 引导）
	private static Method getViewportMethod;
	private static Field pipelineField;
	private static Field fbField; // AbstractRenderPipeline.fb（Normal 管线深度源）
	private static Field fbTranslucentField; // IrisVoxyRenderPipeline.fbTranslucent
	private static Field blitField; // NormalRenderPipeline.finalBlit
	private static Field irisBlitField; // IrisVoxyRenderPipeline.depthBlit
	private static Method getDepthTexMethod;
	private static Field glTextureIdField;
	private static Method transformBlitDepthMethod;
	private static Field vanillaProjectionField;
	private static Field modelViewField;

	private VoxyDepthCompat() {
	}

	/**
	 * 在 MTR 绘制之前调用：把 Voxy 的 LOD 深度强制写回当前绑定的帧缓冲（仅深度）。
	 * 生效状态以日志 "[MTR-Voxy] Voxy LOD depth write active" 为准。
	 */
	public static void writeLodDepthToCurrentFramebuffer() {
		if (!initialized) {
			init();
		}
		if (!available) {
			return;
		}
		try {
			final Object viewport = getViewportMethod.invoke(voxyRenderSystem);
			if (viewport == null) {
				return; // Voxy 阴影渲染阶段，无 LOD 可写
			}
			final int targetFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
			if (targetFramebuffer == 0) {
				return; // 默认帧缓冲无法作为深度写入目标（Voxy 自身限制）
			}
			final Object pipeline = pipelineField.get(voxyRenderSystem);
			final Object depthFramebuffer;
			final Field blit;
			if (fbTranslucentField != null && pipeline.getClass().getName().endsWith("IrisVoxyRenderPipeline")) {
				depthFramebuffer = fbTranslucentField.get(pipeline);
				blit = irisBlitField;
			} else {
				depthFramebuffer = fbField.get(pipeline);
				blit = blitField;
			}
			final Object depthTexture = getDepthTexMethod.invoke(depthFramebuffer);
			final int depthTextureId = glTextureIdField.getInt(depthTexture);
			final Matrix4f targetTransform = new Matrix4f((Matrix4f) vanillaProjectionField.get(viewport)).mul((Matrix4f) modelViewField.get(viewport));

			final int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
			final int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
			final int oldElementArrayBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
			final int oldDrawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
			final int oldReadFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
			final int oldActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
			GL13.glActiveTexture(GL13.GL_TEXTURE0);
			final int oldTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			GL13.glActiveTexture(GL13.GL_TEXTURE3);
			final int oldTexture3 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			GL13.glActiveTexture(GL13.GL_TEXTURE0);
			final boolean oldDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
			final boolean oldBlend = GL11.glIsEnabled(GL11.GL_BLEND);
			final boolean oldStencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
			final int oldDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
			final int[] oldColorMask = new int[4];
			GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, oldColorMask);
			final int[] oldViewport = new int[4];
			GL11.glGetIntegerv(GL11.GL_VIEWPORT, oldViewport);

			try {
				// 只写深度：gl_FragDepth 不受颜色掩码影响；LOD 深度为 0/1 的像素 discard，不破坏已有深度
				GL11.glColorMask(false, false, false, false);
				GL11.glDisable(GL11.GL_STENCIL_TEST);
				GL11.glDisable(GL11.GL_BLEND);
				GL11.glEnable(GL11.GL_DEPTH_TEST);
				GL11.glDepthFunc(GL11.GL_LEQUAL);
				transformBlitDepthMethod.invoke(null, blit.get(pipeline), depthTextureId, targetFramebuffer, viewport, targetTransform);
			} finally {
				// 完整恢复 GL 状态
				GL11.glColorMask(oldColorMask[0] != 0, oldColorMask[1] != 0, oldColorMask[2] != 0, oldColorMask[3] != 0);
				if (oldDepthTest) {
					GL11.glEnable(GL11.GL_DEPTH_TEST);
				} else {
					GL11.glDisable(GL11.GL_DEPTH_TEST);
				}
				if (oldBlend) {
					GL11.glEnable(GL11.GL_BLEND);
				} else {
					GL11.glDisable(GL11.GL_BLEND);
				}
				if (oldStencil) {
					GL11.glEnable(GL11.GL_STENCIL_TEST);
				} else {
					GL11.glDisable(GL11.GL_STENCIL_TEST);
				}
				GL11.glDepthFunc(oldDepthFunc);
				GL20.glUseProgram(oldProgram);
				GL30.glBindVertexArray(oldVao);
				GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, oldElementArrayBuffer);
				GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, oldDrawFramebuffer);
				GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldReadFramebuffer);
				GL13.glActiveTexture(GL13.GL_TEXTURE0);
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture0);
				GL13.glActiveTexture(GL13.GL_TEXTURE3);
				GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture3);
				GL13.glActiveTexture(oldActiveTexture);
				GL11.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]);
			}

			if (!loggedActive) {
				loggedActive = true;
				LOGGER.info("[MTR-Voxy] Voxy LOD depth write active (first frame OK, target framebuffer " + targetFramebuffer + ")");
			}
		} catch (Throwable t) {
			if (!loggedError) {
				loggedError = true;
				LOGGER.error("[MTR-Voxy] Voxy depth write failed: {}", t.toString(), t);
			}
		}
	}

	/**
	 * 反射初始化。双路径引导 Voxy 渲染系统实例：
	 * 1) 优先走 IGetVoxyRenderSystem 接口方法 voxy$getRenderSystem()（新版 Voxy）
	 * 2) 找不到方法时，枚举 LevelRenderer 上类型名为 VoxyRenderSystem 的 mixin @Unique 字段（兼容不同版本）
	 * 之后所有 Voxy 类都从该实例的 classloader 加载，兼容 Sinytra Connector（信雅互联）把 Voxy
	 * 放入独立 classloader 的情况。每一步失败都会打印具体原因，便于核对 Voxy 版本差异。
	 */
	private static void init() {
		initialized = true;
		try {
			final Object levelRenderer = Minecraft.getInstance().levelRenderer;
			if (levelRenderer == null) {
				return;
			}

			// 路径 1：接口方法
			Method getter = null;
			for (Method method : levelRenderer.getClass().getMethods()) {
				if ("voxy$getRenderSystem".equals(method.getName())) {
					getter = method;
					break;
				}
			}
			if (getter != null) {
				voxyRenderSystem = getter.invoke(levelRenderer);
			}

			// 路径 2：mixin @Unique 字段枚举（类型名为 VoxyRenderSystem 的字段）
			if (voxyRenderSystem == null) {
				for (Field field : levelRenderer.getClass().getDeclaredFields()) {
					if (field.getType().getName().endsWith("VoxyRenderSystem")) {
						field.setAccessible(true);
						voxyRenderSystem = field.get(levelRenderer);
						if (voxyRenderSystem != null) {
							LOGGER.info("[MTR-Voxy] Located Voxy render system via field '{}'", field.getName());
							break;
						}
					}
				}
			}
			if (voxyRenderSystem == null) {
				LOGGER.info("[MTR-Voxy] Voxy render system not present, depth compat inactive (expected when Voxy is not installed)");
				return;
			}

			// 用 Voxy 实例的 classloader 查找其余类（Connector 场景下系统 classloader 看不到 Voxy 的类）
			final ClassLoader voxyLoader = voxyRenderSystem.getClass().getClassLoader();
			final Class<?> abstractPipelineClass = voxyLoader.loadClass("me.cortex.voxy.client.core.AbstractRenderPipeline");
			final Class<?> normalPipelineClass = voxyLoader.loadClass("me.cortex.voxy.client.core.NormalRenderPipeline");
			final Class<?> fullscreenBlitClass = voxyLoader.loadClass("me.cortex.voxy.client.core.rendering.post.FullscreenBlit");
			final Class<?> viewportClass = voxyLoader.loadClass("me.cortex.voxy.client.core.rendering.Viewport");
			final Class<?> depthFramebufferClass = voxyLoader.loadClass("me.cortex.voxy.client.core.rendering.util.DepthFramebuffer");
			final Class<?> glTextureClass = voxyLoader.loadClass("me.cortex.voxy.client.core.gl.GlTexture");

			getViewportMethod = voxyRenderSystem.getClass().getMethod("getViewport");
			pipelineField = voxyRenderSystem.getClass().getDeclaredField("pipeline");
			pipelineField.setAccessible(true);
			fbField = abstractPipelineClass.getDeclaredField("fb");
			fbField.setAccessible(true);
			blitField = normalPipelineClass.getDeclaredField("finalBlit");
			blitField.setAccessible(true);
			getDepthTexMethod = depthFramebufferClass.getMethod("getDepthTex");
			glTextureIdField = glTextureClass.getField("id");
			transformBlitDepthMethod = abstractPipelineClass.getDeclaredMethod("transformBlitDepth", fullscreenBlitClass, int.class, int.class, viewportClass, Matrix4f.class);
			transformBlitDepthMethod.setAccessible(true);
			vanillaProjectionField = viewportClass.getField("vanillaProjection");
			modelViewField = viewportClass.getField("modelView");

			// Iris 管线（较新版本才有；老版本 Voxy 无此类时忽略）
			try {
				final Class<?> irisPipelineClass = voxyLoader.loadClass("me.cortex.voxy.client.core.IrisVoxyRenderPipeline");
				fbTranslucentField = irisPipelineClass.getDeclaredField("fbTranslucent");
				fbTranslucentField.setAccessible(true);
				irisBlitField = irisPipelineClass.getDeclaredField("depthBlit");
				irisBlitField.setAccessible(true);
			} catch (NoSuchFieldException | ClassNotFoundException ignored) {
			}

			available = true;
			LOGGER.info("[MTR-Voxy] Voxy depth compat initialized OK (render system: {}, pipeline base: {})", voxyRenderSystem.getClass().getName(), normalPipelineClass.getName());
		} catch (Throwable t) {
			LOGGER.error("[MTR-Voxy] Voxy depth compat init failed, the MTR-Voxy compat will NOT take effect: {}", t.toString(), t);
		}
	}

}
