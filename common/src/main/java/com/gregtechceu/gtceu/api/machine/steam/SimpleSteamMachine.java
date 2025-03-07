package com.gregtechceu.gtceu.api.machine.steam;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.IMachineModifyDrops;
import com.gregtechceu.gtceu.api.machine.feature.IUIMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.OverclockingLogic;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.data.damagesource.DamageSources;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.UITemplate;
import com.gregtechceu.gtceu.api.gui.widget.PredicatedImageWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.common.recipe.SteamVentCondition;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.side.fluid.FluidHelper;
import com.lowdragmc.lowdraglib.side.fluid.IFluidStorage;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import it.unimi.dsi.fastutil.longs.LongIntPair;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

/**
 * @author KilaBash
 * @date 2023/3/15
 * @implNote SimpleSteamMachine
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class SimpleSteamMachine extends SteamWorkableMachine implements IMachineModifyDrops, IUIMachine {
    protected static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(SimpleSteamMachine.class, SteamWorkableMachine.MANAGED_FIELD_HOLDER);

    @Persisted
    public final NotifiableItemStackHandler importItems;
    @Persisted
    public final NotifiableItemStackHandler exportItems;

    public SimpleSteamMachine(IMachineBlockEntity holder, boolean isHighPressure, Object... args) {
        super(holder, isHighPressure, args);
        this.importItems = createImportItemHandler(args);
        this.exportItems = createExportItemHandler(args);
    }

    //////////////////////////////////////
    //*****     Initialization     *****//
    //////////////////////////////////////
    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    protected NotifiableFluidTank createSteamTank(Object... args) {
        return new NotifiableFluidTank(this, 1, 16 * FluidHelper.getBucket(), IO.IN);
    }

    protected NotifiableItemStackHandler createImportItemHandler(Object... args) {
        return new NotifiableItemStackHandler(this, getRecipeType().getMaxInputs(ItemRecipeCapability.CAP), IO.IN);
    }

    protected NotifiableItemStackHandler createExportItemHandler(Object... args) {
        return new NotifiableItemStackHandler(this, getRecipeType().getMaxOutputs(ItemRecipeCapability.CAP), IO.OUT);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // Fine, we use it to provide eu cap for recipe, simulating an EU machine.
        capabilitiesProxy.put(IO.IN, EURecipeCapability.CAP, List.of(new SteamEnergyRecipeHandler(steamTank, FluidHelper.getBucket() / 1000d)));
    }

    @Override
    public void onDrops(List<ItemStack> drops, Player entity) {
        clearInventory(drops, importItems.storage);
        clearInventory(drops, exportItems.storage);
    }

    //////////////////////////////////////
    //******     Recipe Logic     ******//
    //////////////////////////////////////

    @Nullable
    @Override
    public GTRecipe modifyRecipe(GTRecipe recipe) {
        var tier =  GTValues.V[GTValues.LV];
        if (isVentingStuck() || RecipeHelper.getRecipeEUtTier(recipe) > tier) {
            return null;
        }
        var copied =  RecipeHelper.applyOverclock(new OverclockingLogic(false) {
            @Override
            protected LongIntPair runOverclockingLogic(@NotNull GTRecipe recipe, long recipeEUt, long maxVoltage, int duration, int amountOC) {
                return LongIntPair.of(isHighPressure ? recipeEUt * 2 : recipeEUt, isHighPressure ? duration : duration * 2);
            }
        }, recipe, tier);
        if (copied == recipe) {
            copied = recipe.copy();
        }
        copied.conditions.add(SteamVentCondition.INSTANCE);
        return copied;
    }

    @Override
    public void onWorking() {
        super.onWorking();
        if (recipeLogic.getProgress() % 10 == 0) {
            tryDoVenting();
        }
    }

    @Override
    public void afterWorking() {
        super.afterWorking();
        tryDoVenting();
    }

    public void tryDoVenting() {
        var machinePos = getPos();
        var ventingSide = getVentFacing();
        if (!isVentingStuck() && getLevel() instanceof ServerLevel serverLevel) {
            serverLevel.getEntitiesOfClass(LivingEntity.class, new AABB(machinePos.relative(ventingSide)), entity -> !(entity instanceof Player player) || !player.isSpectator() && !(player.isCreative()))
                    .forEach(entity -> {
                        entity.hurt(DamageSources.getHeatDamage(), this.isHighPressure ? 12.0f : 6.0f);
                        // TODO ADVANCEMENT
//                        if (entity instanceof ServerPlayer) {
//                            AdvancementTriggers.STEAM_VENT_DEATH.trigger((EntityPlayerMP) entity);
//                        }
                    });
            double posX = machinePos.getX() + 0.5 + ventingSide.getStepX() * 0.6;
            double posY = machinePos.getY() + 0.5 + ventingSide.getStepY() * 0.6;
            double posZ = machinePos.getZ() + 0.5 + ventingSide.getStepZ() * 0.6;

            serverLevel.sendParticles(ParticleTypes.CLOUD, posX, posY, posZ,
                    7 + serverLevel.random.nextInt(3),
                    ventingSide.getStepX() / 2.0,
                    ventingSide.getStepY() / 2.0,
                    ventingSide.getStepZ() / 2.0, 0.1);
            if (ConfigHolder.machines.machineSounds){
                serverLevel.playSound(null, posX, posY, posZ, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 1.0f, 1.0f);
            }
        }

    }

    //////////////////////////////////////
    //***********     GUI    ***********//
    //////////////////////////////////////
    @Override
    public ModularUI createUI(Player entityPlayer) {
        var group = recipeType.createUITemplate(recipeLogic::getProgressPercent, importItems.storage, exportItems.storage, new IFluidStorage[0], new IFluidStorage[0], true, isHighPressure);
        group.addSelfPosition(0, 20);
        return new ModularUI(176, 166, this, entityPlayer)
                .background(GuiTextures.BACKGROUND_STEAM.get(isHighPressure))
                .widget(group)
                .widget(new LabelWidget(5, 5, getBlockState().getBlock().getDescriptionId()))
                .widget(new PredicatedImageWidget(79, 42, 18, 18, GuiTextures.INDICATOR_NO_STEAM.get(isHighPressure))
                        .setPredicate(recipeLogic::isHasNotEnoughEnergy))
                .widget(UITemplate.bindPlayerInventory(entityPlayer.getInventory(), GuiTextures.SLOT_STEAM.get(isHighPressure), 7, 84, true));
    }
}
