package mtr.mappings;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.client.model.FabricModelPredicateProviderRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;
import java.util.function.Supplier;

public interface FabricRegistryUtilities {

	static void registerItemModelPredicate(String id, Item item, String tag) {
		FabricModelPredicateProviderRegistry.register(item, new ResourceLocation(id), (itemStack, clientWorld, livingEntity, i) -> itemStack.getOrCreateTag().contains(tag) ? 1 : 0);
	}

	static void registerCommand(Consumer<CommandDispatcher<CommandSourceStack>> callback) {
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated, commandSelection) -> callback.accept(dispatcher));
	}

	static void registerCreativeModeTab(CreativeModeTab creativeModeTab, Item item) {
		ItemGroupEvents.MODIFY_ENTRIES_ALL.register((tab, entries) -> {
			if (tab == creativeModeTab) entries.accept(item);
		});
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
}
