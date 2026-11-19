package net.p3pp3rf1y.sophisticatedbackpacks.compat.curios;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.ForgeRegistries;
import net.p3pp3rf1y.sophisticatedbackpacks.SophisticatedBackpacks;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import net.p3pp3rf1y.sophisticatedbackpacks.compat.CompatModIds;
import net.p3pp3rf1y.sophisticatedbackpacks.init.ModItems;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedbackpacks.upgrades.refill.RefillUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.compat.ICompat;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.CuriosCapability;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.SlotTypeMessage;
import top.theillusivec4.curios.api.SlotTypePreset;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

public class CuriosCompat implements ICompat {
	private static final String CHARM_SLOT = SlotTypePreset.CHARM.getIdentifier();

	public static int getMissingCountInCharmSlot(Player player, ItemStack filter) {
		return CuriosApi.getCuriosHelper().getCuriosHandler(player)
				.map(handler -> handler.getStacksHandler(CHARM_SLOT).map(stacksHandler -> {
					int missingCount = 0;
					for (int slot = 0; slot < stacksHandler.getStacks().getSlots(); slot++) {
						ItemStack charm = stacksHandler.getStacks().getStackInSlot(slot);
						if (charm.isEmpty()) {
							if (isValidCharm(player, slot, filter)) {
								missingCount += filter.getMaxStackSize();
							}
						} else if (ItemHandlerHelper.canItemStacksStack(charm, filter)) {
							missingCount += Math.max(0, charm.getMaxStackSize() - charm.getCount());
						}
					}
					return Math.min(missingCount, filter.getMaxStackSize());
				}).orElse(0))
				.orElse(0);
	}

	public static ItemStack refillCharmSlot(Player player, ItemStack stackToAdd) {
		return CuriosApi.getCuriosHelper().getCuriosHandler(player)
				.map(handler -> handler.getStacksHandler(CHARM_SLOT).map(stacksHandler -> {
					ItemStack remaining = stackToAdd;
					for (int slot = 0; slot < stacksHandler.getStacks().getSlots() && !remaining.isEmpty(); slot++) {
						ItemStack currentCharm = stacksHandler.getStacks().getStackInSlot(slot);
						if (!currentCharm.isEmpty() && !ItemHandlerHelper.canItemStacksStack(currentCharm, remaining)) {
							continue;
						}
						if (currentCharm.isEmpty() && !isValidCharm(player, slot, remaining)) {
							continue;
						}

						int slotCapacity = currentCharm.isEmpty() ? remaining.getMaxStackSize() : currentCharm.getMaxStackSize() - currentCharm.getCount();
						int countToMove = Math.min(slotCapacity, remaining.getCount());
						if (countToMove <= 0) {
							continue;
						}

						ItemStack charmToEquip = currentCharm.isEmpty() ? remaining.copy() : currentCharm.copy();
						if (currentCharm.isEmpty()) {
							charmToEquip.setCount(countToMove);
						} else {
							charmToEquip.grow(countToMove);
						}
						CuriosApi.getCuriosHelper().setEquippedCurio(player, CHARM_SLOT, slot, charmToEquip);
						remaining = remaining.copy();
						remaining.shrink(countToMove);
					}
					return remaining;
				}).orElse(stackToAdd))
				.orElse(stackToAdd);
	}

	private static boolean isValidCharm(Player player, int slot, ItemStack stack) {
		SlotContext slotContext = new SlotContext(CHARM_SLOT, player, slot, false, true);
		return CuriosApi.getCuriosHelper().isStackValid(slotContext, stack);
	}

	public CuriosCompat() {
		IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
		modEventBus.addListener(this::sendImc);
	}

	private void addPlayerInventoryHandlers() {
		PlayerInventoryProvider.get().addPlayerInventoryHandler(CompatModIds.CURIOS, this::getCurioTags,
				(player, identifier) -> getFromCuriosSlotStackHandler(player, identifier, ICurioStacksHandler::getSlots, 0),
				(player, identifier, slot) -> getFromCuriosSlotStackHandler(player, identifier, sh -> sh.getStacks().getStackInSlot(slot), ItemStack.EMPTY),
				false, true, true, true);
	}

	private Set<String> backpackCurioIdentifiers = new HashSet<>();
	private long lastTagsRefresh = -1;
	private static final int TAGS_REFRESH_COOLDOWN = 100;

	private Set<String> getCurioTags(long gameTime) {
		if (lastTagsRefresh + TAGS_REFRESH_COOLDOWN < gameTime) {
			lastTagsRefresh = gameTime;
			backpackCurioIdentifiers = new HashSet<>(CuriosApi.getCuriosHelper().getCurioTags(ModItems.BACKPACK.get()));
			backpackCurioIdentifiers.add(SlotTypePreset.CURIO.getIdentifier());
		}
		return backpackCurioIdentifiers;
	}

	public static <T> T getFromCuriosSlotStackHandler(LivingEntity livingEntity, String identifier, Function<ICurioStacksHandler, T> getFromHandler, T defaultValue) {
		return CuriosApi.getCuriosHelper().getCuriosHandler(livingEntity)
				.map(h -> h.getStacksHandler(identifier).map(getFromHandler).orElse(defaultValue)).orElse(defaultValue);
	}

	private void sendImc(InterModEnqueueEvent evt) {
		InterModComms.sendTo(CompatModIds.CURIOS, SlotTypeMessage.REGISTER_TYPE, () -> SlotTypePreset.BACK.getMessageBuilder().build());
	}

	public void onAttachCapabilities(AttachCapabilitiesEvent<ItemStack> evt) {
		ItemStack stack = evt.getObject();
		Item item = stack.getItem();
		ResourceLocation registryName = ForgeRegistries.ITEMS.getKey(item);
		if (registryName != null && registryName.getNamespace().equals(SophisticatedBackpacks.MOD_ID) && item instanceof BackpackItem) {
			evt.addCapability(new ResourceLocation(SophisticatedBackpacks.MOD_ID, registryName.getPath() + "_curios"), new ICapabilityProvider() {
				@Nonnull
				@Override
				public <T> LazyOptional<T> getCapability(Capability<T> cap, @Nullable Direction side) {
					return CuriosCapability.ITEM.orEmpty(cap, LazyOptional.of(() -> () -> stack));
				}
			});
		}
	}

	@Override
	public void setup() {
		RefillUpgradeWrapper.setCharmSlotHandler(new RefillUpgradeWrapper.CharmSlotHandler() {
			@Override
			public int getMissingCount(Player player, ItemStack filter) {
				return getMissingCountInCharmSlot(player, filter);
			}

			@Override
			public ItemStack refill(Player player, ItemStack stackToAdd) {
				return refillCharmSlot(player, stackToAdd);
			}
		});

		DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
			CuriosRendererRegistry.register(ModItems.BACKPACK.get(), BackpackCurioRenderer::new);
			CuriosRendererRegistry.register(ModItems.COPPER_BACKPACK.get(), BackpackCurioRenderer::new);
			CuriosRendererRegistry.register(ModItems.IRON_BACKPACK.get(), BackpackCurioRenderer::new);
			CuriosRendererRegistry.register(ModItems.GOLD_BACKPACK.get(), BackpackCurioRenderer::new);
			CuriosRendererRegistry.register(ModItems.DIAMOND_BACKPACK.get(), BackpackCurioRenderer::new);
			CuriosRendererRegistry.register(ModItems.NETHERITE_BACKPACK.get(), BackpackCurioRenderer::new);
		});

		IEventBus eventBus = MinecraftForge.EVENT_BUS;
		eventBus.addGenericListener(ItemStack.class, this::onAttachCapabilities);

		addPlayerInventoryHandlers();
	}
}
