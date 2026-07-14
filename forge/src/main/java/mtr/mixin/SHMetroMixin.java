package mtr.mixin;

import com.xhg78999.shmetro.mappings.ForgeUtilities;
import com.xhg78999.shmetro.mappings.ForgeUtilities.ClientsideEvents;
import com.xhg78999.shmetro.mappings.ForgeUtilities.Events;
import com.xhg78999.shmetro.mappings.ForgeUtilities.RegisterCreativeTabs;
import dev.architectury.platform.forge.EventBuses;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.registries.DeferredRegister;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(value = ForgeUtilities.class, remap = false)
public abstract class SHMetroMixin {
    @Shadow(remap = false)
    private static DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS;
    @Overwrite(remap = false)
    public static void registerModEventBus(String modId, IEventBus eventBus) {
        EventBuses.registerModEventBus(modId, eventBus);
        CREATIVE_MODE_TABS.register(eventBus);
        eventBus.addListener(RegisterCreativeTabs::onRegisterCreativeModeTabsEvent);

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            eventBus.addListener(ClientsideEvents::onEntityRendererEvent);
            eventBus.addListener(ClientsideEvents::onTextureStitchEvent);
            MinecraftForge.EVENT_BUS.addListener(Events::onRenderTickEvent);
            MinecraftForge.EVENT_BUS.addListener(Events::onRenderGameOverlayEvent);
        });
    }
}