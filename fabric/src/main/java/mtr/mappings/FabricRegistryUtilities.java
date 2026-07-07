package mtr.mappings;

import com.mojang.brigadier.CommandDispatcher;
import mtr.MTR;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.client.model.FabricModelPredicateProviderRegistry;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public interface FabricRegistryUtilities {

	Map<ResourceLocation, List<Item>> CONNECTOR_CREATIVE_TAB_ITEMS = new LinkedHashMap<>();
	boolean[] connectorRegistered = {false};

	static void registerItemModelPredicate(String id, Item item, String tag) {
		FabricModelPredicateProviderRegistry.register(item, new ResourceLocation(id), (itemStack, clientWorld, livingEntity, i) -> itemStack.getOrCreateTag().contains(tag) ? 1 : 0);
	}

	static <T extends BlockEntityMapper> void registerTileEntityRenderer(BlockEntityType<T> type, Function<BlockEntityRenderDispatcher, BlockEntityRendererMapper<T>> factory) {
		BlockEntityRendererRegistry.register(type, context -> factory.apply(null));
	}

	static <T extends Entity> void registerEntityRenderer(EntityType<T> type, Function<EntityRendererProvider.Context, EntityRendererMapper<T>> factory) {
		EntityRendererRegistry.register(type, factory::apply);
	}

	static void registerCommand(Consumer<CommandDispatcher<CommandSourceStack>> callback) {
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated, commandSelection) -> callback.accept(dispatcher));
	}

	static void registerCreativeModeTab(CreativeModeTab creativeModeTab, Item item) {
		ItemGroupEvents.MODIFY_ENTRIES_ALL.register((tab, entries) -> {
			if (tab == creativeModeTab) entries.accept(item);
		});

		final ResourceLocation tabKey = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(creativeModeTab);
		if (tabKey != null) {
			CONNECTOR_CREATIVE_TAB_ITEMS.computeIfAbsent(tabKey, k -> new ArrayList<>()).add(item);
		}
	}

	static CreativeModeTab createCreativeModeTab(ResourceLocation id, Supplier<ItemStack> supplier) {
		String normalizedPath = id.getPath().startsWith(id.getNamespace() + "_")
				? id.getPath().substring(id.getNamespace().length() + 1) : id.getPath();
		CreativeModeTab tab = FabricItemGroup.builder()
				.icon(supplier)
				.title(Text.translatable(String.format("itemGroup.%s.%s", id.getNamespace(), normalizedPath)))
				.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id, tab);
		return tab;
	}

	static void registerConnectorCreativeTabsFallback() {
		if (connectorRegistered[0]) {
			return;
		}
		connectorRegistered[0] = true;

		try {
			Class<?> mcForgeClass = Class.forName("net.minecraftforge.common.MinecraftForge");
			Field eventBusField = mcForgeClass.getField("EVENT_BUS");
			Object eventBus = eventBusField.get(null);

			Method addListenerMethod = null;
			for (Method m : eventBus.getClass().getMethods()) {
				if (m.getName().equals("addListener") && m.getParameterCount() == 1
						&& m.getParameterTypes()[0] == Consumer.class) {
					addListenerMethod = m;
					break;
				}
			}

			if (addListenerMethod == null) {
				return;
			}

			Consumer<Object> handler = eventObj -> {
				try {
					Method getTab = eventObj.getClass().getMethod("getTab");
					Method getEntries = eventObj.getClass().getMethod("getEntries");

					CreativeModeTab tab = (CreativeModeTab) getTab.invoke(eventObj);
					CreativeModeTab.Output output = (CreativeModeTab.Output) getEntries.invoke(eventObj);

					ResourceLocation tabKey = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
					if (tabKey != null) {
						List<Item> items = CONNECTOR_CREATIVE_TAB_ITEMS.get(tabKey);
						if (items != null) {
							for (Item item : items) {
								output.accept(new ItemStack(item));
							}
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			};

			addListenerMethod.invoke(eventBus, handler);
		} catch (ClassNotFoundException ignored) {
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
