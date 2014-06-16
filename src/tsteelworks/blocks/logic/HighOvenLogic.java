package tsteelworks.blocks.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.BlockSourceImpl;
import net.minecraft.dispenser.BehaviorDefaultDispenseItem;
import net.minecraft.dispenser.IBehaviorDispenseItem;
import net.minecraft.dispenser.IRegistry;
import net.minecraft.dispenser.RegistryDefaulted;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.INetworkManager;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.Packet132TileEntityData;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidTank;
import tconstruct.library.crafting.FluidType;
import tconstruct.library.util.CoordTuple;
import tsteelworks.TSteelworks;
import tsteelworks.common.TSContent;
import tsteelworks.common.TSRepo;
import tsteelworks.inventory.HighOvenContainer;
import tsteelworks.lib.ConfigCore;
import tsteelworks.lib.IActiveLogic;
import tsteelworks.lib.IFacingLogic;
import tsteelworks.lib.IMasterLogic;
import tsteelworks.lib.IRedstonePowered;
import tsteelworks.lib.IServantLogic;
import tsteelworks.lib.TSFuelHandler;
import tsteelworks.lib.blocks.TSInventoryLogic;
import tsteelworks.lib.crafting.AdvancedSmelting;
import tsteelworks.util.InventoryHelper;
import cpw.mods.fml.common.registry.GameRegistry;

/**
 * The primary class for the High Oven structure's logic.
 */
public class HighOvenLogic extends TSInventoryLogic implements IActiveLogic, IFacingLogic, IFluidTank, IMasterLogic, IRedstonePowered {

    /**
     * Oxidizer Slot - Redox agent.
     * (gunpowder, sugar, etc)
     */
    public static final int SLOT_OXIDIZER = 0;

    /**
     * Reducer Slot - redox agent.
     * (redstone dust, aluminum dust, etc)
     */
    public static final int SLOT_REDUCER = 1;

    /**
     * Purifier Slot - purifying agent.
     * (sand, graveyard soil, etc)
     */
    public static final int SLOT_PURIFIER = 2;

    /**
     * Fuel Slot - cook things.
     * (charcoal, charcoal block, etc)
     */
    public static final int SLOT_FUEL = 3;

    /**
     * First meltable item slot.
     * All slots after this will also be meltable.
     * (Iron Ore, Iron Ingot, etc)
     */
    public static final int SLOT_FIRST_MELTABLE = 4;

    /** The amount of fluid the tank may gain per layer - multiplier. */
    public static final int FLUID_AMOUNT_PER_LAYER = 20000;

    /** The max temperature of the smallest High Oven structure. */
    public static final int DEFAULT_MAX_TEMP = 2000;

    /** Temperature the High Oven defaults to when not cooking. */
    public static final int ROOM_TEMP = 20;

    /** The dispense behavior. */
    private final IRegistry dispenseBehavior = new RegistryDefaulted(new BehaviorDefaultDispenseItem());

    /** The molten metal. */
    private final ArrayList<FluidStack> fluidlist = new ArrayList<FluidStack>();

    /** Used to determine if the structure has a bottom. */
    private boolean structureHasBottom;

    /** Used to determine if the structure has a top. */
    private boolean structureHasTop;

    /**
     * Used to determine if the controller is being supplied with a redstone
     * signal.
     */
    private boolean redstoneActivated;

    /** Used to determine if the structure needs to be updated. */
    private boolean needsUpdate;

    /** Used to determine if the controller is melting items. */
    private boolean isMeltingItems;

    /** Used to determine if the structure is valid. */
    private boolean validStructure;

    /** Used to determine the controller's facing direction. */
    private byte direction;

    /** The coordinates of the structure's output duct. */
    private CoordTuple outputDuct;

    /** The coordinates of the structure's absolute center position. */
    private CoordTuple centerPos;

    /** The internal temperature. */
    private int internalTemp;

    /** The current fuel heat rate (gain). */
    private int fuelHeatRate;

    /** The internal cool down rate. */
    private int internalCoolDownRate;

    /** Tick tock, tick tock. */
    private int tick;

    /** The max liquid capacity. */
    private int maxLiquid;

    /** The current liquid amount. */
    private int currentLiquid;

    /** The amount of blocks in the structure. */
    private int numBricks;

    /** The max temperature. */
    private int maxTemp;

    /** The max tempperature set by the user (not implemented). */
    private int maxTempUserSet;

    /** The amount of layers. */
    private int layers;

    /** The fuel burn time. */
    private int fuelBurnTime;

    /** The active temperatures of melting items. */
    private int[] activeTemps;

    /** The melting point temperatures if melting items. */
    private int[] meltingTemps;

    /** Used to randomize things. */
    private Random rand = new Random();

    /**
     * Initialization.
     */
    public HighOvenLogic() {
        super(4);
        this.setRSmode(false);
        this.fuelHeatRate = 3;
        this.internalCoolDownRate = 10;
        this.activeTemps = this.meltingTemps = new int[0];
        this.maxTemp = this.maxTempUserSet = DEFAULT_MAX_TEMP;
    }

    /* ==================== Layers ==================== */

    /**
     * Adjust Layers for inventory containment.
     * 
     * @param lay
     *        Layer
     * @param forceAdjust
     *        the force adjust
     */
    final void adjustLayers(final int lay, final boolean forceAdjust) {
        if ((lay != this.layers) || forceAdjust) {
            this.needsUpdate = true;
            this.layers = lay;
            // TSteelworks.loginfo("layers", layers);
            this.maxLiquid = FLUID_AMOUNT_PER_LAYER * lay;
            this.maxTemp = this.maxTempByLayer();
            final int[] tempActive = this.activeTemps;
            this.activeTemps = new int[SLOT_FIRST_MELTABLE + lay];
            final int activeLength = tempActive.length > this.activeTemps.length ? this.activeTemps.length : tempActive.length;
            System.arraycopy(tempActive, 0, this.activeTemps, 0, activeLength);
            final int[] tempMelting = this.meltingTemps;
            this.meltingTemps = new int[SLOT_FIRST_MELTABLE + lay];
            final int meltingLength = tempMelting.length > this.meltingTemps.length ? this.meltingTemps.length : tempMelting.length;
            System.arraycopy(tempMelting, 0, this.meltingTemps, 0, meltingLength);
            final ItemStack[] tempInv = this.inventory;

            // maybe we should try to split inventory (and the others arrays) it
            // two.
            // One fixed inventory for slot 0..3 which will remains the same
            // and a variable size inventory depending on the number of layers
            // for the (s)meltable.
            // Toops notes: Agreed...
            this.inventory = new ItemStack[SLOT_FIRST_MELTABLE + lay];
            final int invLength = tempInv.length > this.inventory.length ? this.inventory.length : tempInv.length;
            System.arraycopy(tempInv, 0, this.inventory, 0, invLength);
            if ((this.activeTemps.length > 0) && (this.activeTemps.length > tempActive.length)) {
                for (int i = tempActive.length; i < this.activeTemps.length; i++) {
                    if (!this.isSmeltingSlot(i)) {
                        continue;
                    }
                    this.activeTemps[i] = ROOM_TEMP;
                    this.meltingTemps[i] = ROOM_TEMP;
                }
            }
            if (tempInv.length > this.inventory.length) {
                for (int i = this.inventory.length; i < tempInv.length; i++) {
                    final ItemStack stack = tempInv[i];
                    if (stack != null) {
                        final float jumpX = (this.rand.nextFloat() * 0.8F) + 0.1F;
                        final float jumpY = (this.rand.nextFloat() * 0.8F) + 0.1F;
                        final float jumpZ = (this.rand.nextFloat() * 0.8F) + 0.1F;
                        int offsetX = 0;
                        int offsetZ = 0;
                        switch (this.getRenderDirection()) {
                            case 2: // +z
                                offsetZ = -1;
                                break;
                            case 3: // -z
                                offsetZ = 1;
                                break;
                            case 4: // +x
                                offsetX = -1;
                                break;
                            case 5: // -x
                                offsetX = 1;
                                break;
                            default:
                                break;
                        }

                        while (stack.stackSize > 0) {
                            int itemSize = this.rand.nextInt(21) + 10;
                            if (itemSize > stack.stackSize) {
                                itemSize = stack.stackSize;
                            }
                            stack.stackSize -= itemSize;
                            final EntityItem entityitem = new EntityItem(this.worldObj, this.xCoord + jumpX + offsetX, 
                                                                         this.yCoord + jumpY, this.zCoord + jumpZ + offsetZ, 
                                                                         new ItemStack(stack.itemID, itemSize, stack.getItemDamage()));

                            if (stack.hasTagCompound()) {
                                entityitem.getEntityItem().setTagCompound((NBTTagCompound) stack.getTagCompound().copy());
                            }
                            final float offset = 0.05F;
                            entityitem.motionX = (float) this.rand.nextGaussian() * offset;
                            entityitem.motionY = ((float) this.rand.nextGaussian() * offset) + 0.2F;
                            entityitem.motionZ = (float) this.rand.nextGaussian() * offset;
                            this.worldObj.spawnEntityInWorld(entityitem);
                        }
                    }
                }
            }
        }
    }

    /**
     * Max temp by layer.
     * 
     * @return the int
     */
    public final int maxTempByLayer() {
        final int layerAdjustment = (this.layers - 1) * 500;
        final int finalCalc = (DEFAULT_MAX_TEMP + layerAdjustment) < DEFAULT_MAX_TEMP ? DEFAULT_MAX_TEMP : DEFAULT_MAX_TEMP + layerAdjustment;
        return finalCalc;
    }

    /**
     * Gets the amount of layers in the structure.
     *
     * @return the layers
     */
    public final int getLayers() {
        return this.layers;
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see net.minecraft.tileentity.TileEntity#canUpdate()
     */
    @Override
    public final boolean canUpdate() {
        return true;
    }

    /* ==================== Misc ==================== */

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.blocks.TSInventoryLogic#getDefaultName()
     */
    @Override
    public final String getDefaultName() {
        return "crafters.HighOven";
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * tsteelworks.lib.blocks.TSInventoryLogic#getGuiContainer(net.minecraft
     * .entity.player.InventoryPlayer, net.minecraft.world.World, int, int, int)
     */
    @Override
    public final Container getGuiContainer(final InventoryPlayer inventoryplayer, final World world, final int x, final int y, final int z) {
        return new HighOvenContainer(inventoryplayer, this);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * tsteelworks.lib.blocks.TSInventoryLogic#isUseableByPlayer(net.minecraft
     * .entity.player.EntityPlayer)
     */
    @Override
    public final boolean isUseableByPlayer(final EntityPlayer entityplayer) {
        if (worldObj.getBlockTileEntity(this.xCoord, this.yCoord, this.zCoord) != this) {
            return false;
        } else {
            return entityplayer.getDistance(this.xCoord + 0.5D, this.yCoord + 0.5D, this.zCoord + 0.5D) <= 64D;
        }
    }

    /* ==================== Facing Logic ==================== */

    /*
     * (non-Javadoc)
     * 
     * @see tconstruct.library.util.IFacingLogic#getRenderDirection()
     */
    @Override
    public final byte getRenderDirection() {
        return this.direction;
    }

    /*
     * (non-Javadoc)
     * 
     * @see tconstruct.library.util.IFacingLogic#getForgeDirection()
     */
    @Override
    public final ForgeDirection getForgeDirection() {
        return ForgeDirection.VALID_DIRECTIONS[this.direction];
    }

    /*
     * (non-Javadoc)
     * 
     * @see tconstruct.library.util.IFacingLogic#setDirection(int)
     */
    @Override
    public void setDirection(final int side) { }

    /*
     * (non-Javadoc)
     * 
     * @see tconstruct.library.util.IFacingLogic#setDirection(float, float,
     * net.minecraft.entity.EntityLivingBase)
     */
    @Override
    public final void setDirection(final float yaw, final float pitch, final EntityLivingBase player) {
        final int facing = MathHelper.floor_double((yaw / 360) + 0.5D) & 3;
        switch (facing) {
            case 0:
                this.direction = 2;
                break;
            case 1:
                this.direction = 5;
                break;
            case 2:
                this.direction = 3;
                break;
            case 3:
                this.direction = 4;
                break;
            default:
                break;
        }
    }

    /* ==================== Active Logic ==================== */

    /*
     * (non-Javadoc)
     * 
     * @see tconstruct.library.util.IActiveLogic#getActive()
     */
    @Override
    public final boolean getActive() {
        return this.validStructure && this.isBurning();
    }

    /*
     * (non-Javadoc)
     * 
     * @see tconstruct.library.util.IActiveLogic#setActive(boolean)
     */
    @Override
    public final void setActive(final boolean flag) {
        this.needsUpdate = true;
        worldObj.markBlockForUpdate(this.xCoord, this.yCoord, this.zCoord);
    }

    /* ==================== IRedstonePowered ==================== */

    /**
     * Get the current state of redstone-connected power.
     * 
     * @return Redstone powered state
     */
    @Override
    public final boolean getRSmode() {
        return this.redstoneActivated;
    }

    /**
     * Set the redstone powered state.
     * 
     * @param flag
     *        true: powered / false: not powered
     */
    @Override
    public final void setRSmode(final boolean flag) {
        this.redstoneActivated = flag;
        this.setActive(true);
    }

    /* ==================== Smelting Logic ==================== */

    /**
     * Update Tile Entity.
     * 
     * @see net.minecraft.tileentity.TileEntity#updateEntity()
     */
    @Override
    public final void updateEntity() {
        this.tick++;
        // item smelting
        if ((this.tick % 4) == 0) {
            this.heatItems();
        }
        // structural checks amd fuel guage updates
        if ((this.tick % 20) == 0) {
            if (!this.validStructure) {
                this.checkValidPlacement();
            }
            if (this.isBurning()) {
                this.fuelBurnTime -= 3;
                this.internalTemp = Math.min(this.internalTemp + this.fuelHeatRate, this.maxTempByLayer());
            } else {
                this.internalTemp = Math.max(this.internalTemp - this.internalCoolDownRate, ROOM_TEMP);
            }
            if (this.validStructure && (this.fuelBurnTime <= 0)) {
                this.updateFuelGauge();
            }
            if (this.needsUpdate) {
                this.needsUpdate = false;
                worldObj.markBlockForUpdate(this.xCoord, this.yCoord, this.zCoord);
            }
        }
        // fluid heating (steam)
        if ((this.tick % 40) == 0) {
            this.heatFluids();
        }
        // reset tick to 0, back to the beginning we go~
        if (this.tick == 60) {
            this.tick = 0;
        }
    }

    /**
     * Process item heating and liquifying.
     */
    final void heatItems() {
        if (this.internalTemp > ROOM_TEMP) {
            boolean hasUse = false;
            for (int i = SLOT_FIRST_MELTABLE; i < (this.layers + SLOT_FIRST_MELTABLE); i += 1) {
                // If an item is present and meltable
                if (this.isStackInSlot(i) && (this.meltingTemps[i] > ROOM_TEMP)) {
                    hasUse = true;
                    // Increase temp if its temp is lower than the High Oven's internal temp and hasn't reached melting point
                    if ((this.activeTemps[i] < this.internalTemp) && (this.activeTemps[i] < this.meltingTemps[i])) {
                        this.activeTemps[i] += (this.internalTemp > 250) ? (this.internalTemp / 250) : 1;
                        // Decrease temp if its temp is higher than the High Oven's internal
                        // temp and the High Oven's internal temp is lower than the melting point
                    } else
                        if ((this.activeTemps[i] > this.internalTemp) && (this.internalTemp < this.meltingTemps[i])) {
                            this.activeTemps[i] -= 1;
                            // Liquify metals if the temp has reached the melting point
                        } else
                            if (this.activeTemps[i] >= this.meltingTemps[i]) {
                                if (!worldObj.isRemote) {
                                    final FluidStack result = this.getNormalResultFor(this.inventory[i]);
                                    final ItemStack resultitemstack = this.getSolidMixedResultFor(result);
                                    if (resultitemstack != null) {
                                        if (resultitemstack != null) {
                                            this.meltItemsSolidOutput(i, resultitemstack, true);
                                        }
                                    } else
                                        if (result != null) {
                                            final FluidStack resultEx = this.getLiquidMixedResultFor(result);
                                            if (resultEx != null) {
                                                this.meltItemsLiquidOutput(i, resultEx, true);
                                            } else {
                                                this.meltItemsLiquidOutput(i, result, false);
                                            }
                                        }
                                }
                            }
                } else {
                    this.activeTemps[i] = ROOM_TEMP;
                }
            }
            this.isMeltingItems = hasUse;
        }
    }

    /**
     * Heat fluids.
     */
    final void heatFluids() {
        if ((this.internalTemp < 1300) || (this.fluidlist.size() < 1)) {
            return;
        }
        // Let's make steam!
        if ((this.getFluid().getFluid() == FluidRegistry.WATER) || (this.getFluid().getFluid() == FluidRegistry.getFluid("Steam"))) {
            int amount = 0;
            for (final FluidStack fluid : this.fluidlist) {
                if (fluid.getFluid() == FluidRegistry.WATER) {
                    amount += fluid.amount;
                }
            }
            if (amount > 0) {
                final FluidStack steam = new FluidStack(TSContent.steamFluid.getID(), amount);
                if (this.addFluidToTank(steam, false)) {
                    this.fluidlist.remove(0);
                    this.currentLiquid -= amount;
                }
            }
        }
    }

    /**
     * Melt items liquid output.
     * 
     * @param slot the slot
     * @param fluid the fluid
     * @param doMix the do mix
     */
    final void meltItemsLiquidOutput(final int slot, final FluidStack fluid, final Boolean doMix) {
        if (this.addFluidToTank(fluid, false)) {
            if (InventoryHelper.itemIsOre(this.inventory[slot])) {
                this.outputTE3Slag();
            }

            if (this.inventory[slot].stackSize >= 2) {
                this.inventory[slot].stackSize--;
            } else {
                this.inventory[slot] = null;
            }
            this.activeTemps[slot] = ROOM_TEMP;
            if (doMix) {
                this.removeMixItems();
            }
            this.onInventoryChanged();
        }
    }

    /**
     * Melt items solid output.
     * 
     * @param slot the slot
     * @param stack the stack
     * @param doMix the do mix
     */
    final void meltItemsSolidOutput(final int slot, final ItemStack stack, final Boolean doMix) {
        if (this.inventory[slot].stackSize >= 2) {
            this.inventory[slot].stackSize--;
        } else {
            this.inventory[slot] = null;
        }
        this.activeTemps[slot] = ROOM_TEMP;
        if (doMix) {
            this.removeMixItems();
        }
        this.addItem(stack);
        this.onInventoryChanged();
    }

    /**
     * Gets the normal result for.
     * 
     * @param itemstack the stack
     * @return the normal result for
     */
    public final FluidStack getNormalResultFor(final ItemStack itemstack) {
        return AdvancedSmelting.getMeltingResult(itemstack);
    }

    /**
     * Gets the liquid mixed result for.
     * 
     * @param fluidstack
     *        the stack
     * @return the liquid mixed result for
     */
    public final FluidStack getLiquidMixedResultFor(final FluidStack fluidstack) {
        final FluidType resultType = FluidType.getFluidType(fluidstack.getFluid());
        final FluidType mixResult = AdvancedSmelting.getMixFluidSmeltingResult(resultType, this.inventory[SLOT_OXIDIZER], this.inventory[SLOT_REDUCER],
                                                                               this.inventory[SLOT_PURIFIER]);
        if (mixResult != null) {
            return new FluidStack(mixResult.fluid, fluidstack.amount);
        }
        return null;
    }

    /**
     * Gets the solid mixed result for.
     * 
     * @param fluidstack the stack
     * @return the solid mixed result for
     */
    public final ItemStack getSolidMixedResultFor(final FluidStack fluidstack) {
        final FluidType resultType = FluidType.getFluidType(fluidstack.getFluid());
        final ItemStack mixResult = AdvancedSmelting.getMixItemSmeltingResult(resultType, this.inventory[SLOT_OXIDIZER], this.inventory[SLOT_REDUCER],
                                                                              this.inventory[SLOT_PURIFIER]);
        if (mixResult != null) {
            return new ItemStack(mixResult.itemID, mixResult.stackSize, mixResult.getItemDamage());
        }
        return null;
    }

    /**
     * Output Thermal Expansion 3 slag if available.
     */
    private void outputTE3Slag() {
        if (TSteelworks.thermalExpansionAvailable && ConfigCore.enableTE3SlagOutput) {
            if (new Random().nextInt(100) <= 10) {
                this.addItem(GameRegistry.findItemStack("ThermalExpansion", "slag", 1));
            }
        }
    }

    /**
     * Remove additive materials by preset vs random chance and amount.
     */
    private void removeMixItems() {
        // using SLOT_0 and SLOT_FIRST_MELTABLE - 1? another reason to split
        // inventory between FixedSizeInventoy for 0..3 and
        // VariableSizeInventory for meltables
        for (int i = SLOT_OXIDIZER; i < SLOT_FUEL; i++) {
            if (this.inventory[i] == null) {
                continue;
            }

            final int consumeChance = AdvancedSmelting.getMixItemConsumeChance(this.inventory[i]);
            final int consumeAmount = AdvancedSmelting.getMixItemConsumeAmount(this.inventory[i]);
            if (new Random().nextInt(100) <= consumeChance) {
                if (this.inventory[i].stackSize >= consumeAmount) {
                    this.inventory[i].stackSize -= consumeAmount;
                }
            }
            if ((this.inventory[i] != null) && (this.inventory[i].stackSize == 0)) {
                this.inventory[i] = null;
            }
        }
    }

    /* ==================== Temperatures ==================== */

    /**
     * Get internal temperature for smelting.
     * 
     * @return internal temperature value
     */
    public final int getInternalTemperature() {
        return this.internalTemp;
    }

    /**
     * Get current temperature for slot.
     *
     * @param slot the slot
     * @return the temp for slot
     */
    public final int getTempForSlot(final int slot) {
        return (this.isSmeltingSlot(slot)) ? this.activeTemps[slot] : ROOM_TEMP;
    }

    /**
     * Get melting point for item in slot.
     * 
     * @param slot the slot
     * @return the melting point for slot
     */
    public final int getMeltingPointForSlot(final int slot) {
        return (this.isSmeltingSlot(slot)) ? this.meltingTemps[slot] : ROOM_TEMP;
    }

    /**
     * Update melting temperatures for items.
     */
    final void updateTemperatures() {
        this.isMeltingItems = true;
        for (int i = SLOT_FIRST_MELTABLE; i < (this.layers + SLOT_FIRST_MELTABLE); i += 1) {
            this.meltingTemps[i] = AdvancedSmelting.getLiquifyTemperature(this.inventory[i]);
        }
    }

    /* ==================== Fuel Handling ==================== */

    /**
     * Checks if controller is burning fuel.
     * 
     * @return true, burning
     */
    public final boolean isBurning() {
        return this.fuelBurnTime > 0;
    }

    /**
     * Checks for fuel available.
     * 
     * @return true, fuel is available
     */
    public final boolean hasFuel() {
        return HighOvenLogic.getFuelBurnTime(this.inventory[SLOT_FUEL]) > 0;
    }

    /**
     * Get fuel gauge scaled for display.
     * 
     * @param scale the scale
     * @return scaled value
     */
    public final int getScaledFuelGauge(final int scale) {
        final int value = this.fuelBurnTime / scale;
        return value < 1 ? 1 : value;
    }

    /**
     * Update fuel gauge.
     */
    final void updateFuelGauge() {
        if (this.isBurning() || !this.getRSmode()) {
            return;
        }
        if (this.inventory[SLOT_FUEL] == null) {
            this.fuelBurnTime = 0;
            return;
        }
        if (HighOvenLogic.getFuelBurnTime(this.inventory[SLOT_FUEL]) > 0) {
            this.needsUpdate = true;
            this.fuelBurnTime = HighOvenLogic.getFuelBurnTime(this.inventory[SLOT_FUEL]);
            this.fuelHeatRate = HighOvenLogic.getFuelHeatRate(this.inventory[SLOT_FUEL]);
            this.inventory[SLOT_FUEL].stackSize--;
            if (this.inventory[SLOT_FUEL].stackSize <= 0) {
                this.inventory[SLOT_FUEL] = null;
            }
        }
    }

    /**
     * Update fuel gauge display.
     */
    public final void updateFuelDisplay() {
        if (HighOvenLogic.getFuelBurnTime(this.inventory[SLOT_FUEL]) > 0) {
            this.needsUpdate = true;
            worldObj.markBlockForUpdate(this.xCoord, this.yCoord, this.zCoord);
        }
    }

    /**
     * Gets the current fuel burn time.
     *
     * @return the fuel burn time
     */
    public final int getFuelBurnTime() {
        return this.fuelBurnTime;
    }
    
    /**
     * Sets the current fuel burn time.
     *
     * @param value the new fuel burn time
     */
    public final void setFuelBurnTime(final int value) {
        this.fuelBurnTime = value;
    }
    
    /**
     * Gets the fuel burn time by given item from the fuel handler.
     * 
     * @param itemstack
     *        the stack
     * @return the fuel burn time
     */
    public static int getFuelBurnTime(final ItemStack itemstack) {
        if (itemstack == null) {
            return 0;
        }
        return TSteelworks.fuelHandler.getHighOvenFuelBurnTime(itemstack);
    }

    /**
     * Gets the current fuel heat rate.
     *
     * @return the fuel heat rate
     */
    public final int getFuelHeatRate() {
        return this.fuelHeatRate;
    }
    
    /**
     * Sets the current fuel heat rate.
     *
     * @param value the new fuel heat rate
     */
    public final void setFuelHeatRate(final int value) {
        this.fuelHeatRate = value;
    }
    
    /**
     * Get the rate of heat increase by given item from the fuel handler.
     *
     * @param itemstack the itemstack
     * @return the fuel heat rate
     */
    public static int getFuelHeatRate(final ItemStack itemstack) {
        if (itemstack == null) {
            return 0;
        }
        return TSFuelHandler.getHighOvenFuelHeatRate(itemstack);
    }
    
    /* ==================== Inventory ==================== */

    /**
     * Determine is slot is valid for 'ore' processing.
     * 
     * @param slot the slot
     * @return true if slot is valid
     */
    public final boolean isSmeltingSlot(final int slot) {
        return slot > SLOT_FUEL;
    }

    /* (non-Javadoc)
     * @see tsteelworks.lib.blocks.TSInventoryLogic#getInventoryStackLimit()
     */
    @Override
    public final int getInventoryStackLimit() {
        return 64;
    }

    /**
     * Called when an the contents of Inventory change.
     */
    /*
     * (non-Javadoc)
     * 
     * @see net.minecraft.tileentity.TileEntity#onInventoryChanged()
     */
    @Override
    public final void onInventoryChanged() {
        this.updateTemperatures();
        // updateEntity();
        super.onInventoryChanged();
        this.needsUpdate = true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.blocks.TSInventoryLogic#getSizeInventory()
     */
    @Override
    public final int getSizeInventory() {
        return this.inventory.length;
    }

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.blocks.TSInventoryLogic#getStackInSlot(int)
     */
    @Override
    public final ItemStack getStackInSlot(final int slot) {
        return this.inventory[slot];
    }

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.blocks.TSInventoryLogic#decrStackSize(int, int)
     */
    @Override
    public final ItemStack decrStackSize(final int slot, final int quantity) {
        if (this.inventory[slot] != null) {
            if (this.inventory[slot].stackSize <= quantity) {
                final ItemStack stack = this.inventory[slot];
                this.inventory[slot] = null;
                return stack;
            }
            final ItemStack split = this.inventory[slot].splitStack(quantity);
            if (this.inventory[slot].stackSize == 0) {
                this.inventory[slot] = null;
            }
            return split;
        } else {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.blocks.TSInventoryLogic#getStackInSlotOnClosing(int)
     */
    @Override
    public final ItemStack getStackInSlotOnClosing(final int slot) {
        return null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * tsteelworks.lib.blocks.TSInventoryLogic#setInventorySlotContents(int,
     * net.minecraft.item.ItemStack)
     */
    @Override
    public final void setInventorySlotContents(final int slot, final ItemStack itemstack) {
        this.inventory[slot] = itemstack;
        if ((itemstack != null) && (itemstack.stackSize > this.getInventoryStackLimit())) {
            itemstack.stackSize = this.getInventoryStackLimit();
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.blocks.TSInventoryLogic#getInvName()
     */
    @Override
    public final String getInvName() {
        return this.isInvNameLocalized() ? this.invName : this.getDefaultName();
    }

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.blocks.TSInventoryLogic#isInvNameLocalized()
     */
    @Override
    public final boolean isInvNameLocalized() {
        return (this.invName != null) && (this.invName.length() > 0);
    }

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.blocks.TSInventoryLogic#isItemValidForSlot(int,
     * net.minecraft.item.ItemStack)
     */
    @Override
    public final boolean isItemValidForSlot(final int slot, final ItemStack itemstack) {
        if (slot < this.getSizeInventory()) {
            if ((this.inventory[slot] == null) || ((itemstack.stackSize + this.inventory[slot].stackSize) <= this.getInventoryStackLimit())) {
                return true;
            }
        }
        return false;
    }

    /* ==================== Multiblock ==================== */

    /*
     * (non-Javadoc)
     * 
     * @see
     * tconstruct.library.util.IMasterLogic#notifyChange(tconstruct.library.
     * util.IServantLogic, int, int, int)
     */
    @Override
    public final void notifyChange(final IServantLogic servant, final int x, final int y, final int z) {
        this.checkValidPlacement();
    }

    /**
     * Check placement validation by facing direction.
     */
    @Override
    public final void checkValidPlacement() {
        switch (this.getRenderDirection()) {
            case 2: // +z
                this.alignInitialPlacement(this.xCoord, this.yCoord, this.zCoord + 1);
                break;
            case 3: // -z
                this.alignInitialPlacement(this.xCoord, this.yCoord, this.zCoord - 1);
                break;
            case 4: // +x
                this.alignInitialPlacement(this.xCoord + 1, this.yCoord, this.zCoord);
                break;
            case 5: // -x
                this.alignInitialPlacement(this.xCoord - 1, this.yCoord, this.zCoord);
                break;
            default:
                break;
        }
    }

    /**
     * Begin structure alignment.
     *
     * @param x        coordinate from controller
     * @param y        coordinate from controller
     * @param z        coordinate from controller
     */
    public final void alignInitialPlacement(final int x, final int y, final int z) {
        this.checkValidStructure(x, y, z);
    }

    // TODO Wisthy - 2014/05/02 - solution for issue Toops#22 should be
    // somewhere there. Method should be refactored the same way DTL has been
    // updated
    /**
     * Determine if structure is valid.
     * 
     * @param x
     *        coordinate from controller
     * @param y
     *        coordinate from controller
     * @param z
     *        coordinate from controller
     * @see {@link HighOvenLogic#checkValidStructure(int, int, int)}
     */
    @Deprecated
    public final void checkValidStructureOld(final int x, final int y, final int z) {
        int checkLayers = 0;
        if (this.checkSameLevel(x, y, z)) {
            checkLayers++;
            checkLayers += this.recurseStructureUp(x, y + 1, z, 0);
            checkLayers += this.recurseStructureDown(x, y - 1, z, 0);
        }
        if ((this.structureHasTop != this.structureHasBottom != this.validStructure) || (checkLayers != this.layers)) {
            if (this.structureHasBottom && this.structureHasTop) {
                this.adjustLayers(checkLayers, false);
                worldObj.markBlockForUpdate(this.xCoord, this.yCoord, this.zCoord);
                this.validStructure = true;
            } else {
                this.internalTemp = ROOM_TEMP;
                this.validStructure = false;
            }
        }
    }

    // Wisthy - 2014/05/02 - new method as solution for issue Toops#22
    /**
     * Determine if structure is valid.
     * 
     * @param x
     *        coordinate from controller
     * @param y
     *        coordinate from controller
     * @param z
     *        coordinate from controller
     */
    public final void checkValidStructure(final int x, final int y, final int z) {
        // TSteelworks.loginfo("HOL - checkValidStructure(x="+x+", y="+y+", z="+z+")");
        /*
         * store old validation variables
         */
        final boolean oldStructureHasBottom = this.structureHasBottom;
        final boolean oldStructureHasTop = this.structureHasTop;
        // boolean oldValidStructure = validStructure;

        /*
         * reset all validation variables
         */
        this.structureHasBottom = false;
        this.structureHasTop = false;
        // validStructure = false;

        int checkedLayers = 0;

        if (this.checkSameLevel(x, y, z)) {
            // TSteelworks.loginfo("HOL - checkValidStructure - same level ok");
            checkedLayers++;
            checkedLayers += this.recurseStructureUp(x, y + 1, z, 0);
            // TSteelworks.loginfo("HOL - checkValidStructure - up: "+checkedLayers);
            checkedLayers += this.recurseStructureDown(x, y - 1, z, 0);
            // TSteelworks.loginfo("HOL - checkValidStructure - down: "+checkedLayers);
        }

        // TSteelworks.loginfo("HOL - checkValidStructure - hasBottom: "+structureHasBottom);
        // TSteelworks.loginfo("HOL - checkValidStructure - oldHasBottom: "+oldStructureHasBottom);

        // TSteelworks.loginfo("HOL - checkValidStructure - hasTop: "+structureHasTop);
        // TSteelworks.loginfo("HOL - checkValidStructure - oldHasTop: "+oldStructureHasTop);

        // TSteelworks.loginfo("HOL - checkValidStructure - oldLayers: "+this.layers);

        if ((oldStructureHasBottom != this.structureHasBottom) || (oldStructureHasTop != this.structureHasTop) || (this.layers != checkedLayers)) {
            if (this.structureHasBottom && this.structureHasTop && (checkedLayers > 0)) {
                this.adjustLayers(checkedLayers, false);
                this.validStructure = true;
            } else {
                this.internalTemp = ROOM_TEMP;
                this.validStructure = false;
            }
            worldObj.markBlockForUpdate(this.xCoord, this.yCoord, this.zCoord);
        }
    }

    /**
     * Scan the controller layer of the structure for valid components.
     *
     * @param x        coordinate from center
     * @param y        coordinate from center
     * @param z        coordinate from center
     * @return block count
     */
    public final boolean checkSameLevel(final int x, final int y, final int z) {
        this.numBricks = 0;
        Block block;
        // Check inside
        for (int xPos = x; xPos <= x; xPos++) {
            for (int zPos = z; zPos <= z; zPos++) {
                block = Block.blocksList[worldObj.getBlockId(xPos, y, zPos)];
                if ((block != null) && !block.isAirBlock(worldObj, xPos, y, zPos)) {
                    return false;
                }
            }
        }
        // Check outer layer
        // Scans in a swastica-like pattern
        for (int xPos = x - 1; xPos <= (x + 1); xPos++) {
            this.numBricks += this.checkBricks(xPos, y, z - 1);
            this.numBricks += this.checkBricks(xPos, y, z + 1);
        }
        for (int zPos = z; zPos <= z; zPos++) {
            this.numBricks += this.checkBricks(x - 1, y, zPos);
            this.numBricks += this.checkBricks(x + 1, y, zPos);
        }
        return this.numBricks == 8;
    }

    /**
     * Scan up the structure for valid components.
     *
     * @param x        coordinate from center
     * @param y        coordinate from center
     * @param z        coordinate from center
     * @param count        current amount of blocks
     * @return block count
     */
    public final int recurseStructureUp(final int x, final int y, final int z, final int count) {
        this.numBricks = 0;
        int increment = count;
        // Check inside
        for (int xPos = x; xPos <= x; xPos++) {
            for (int zPos = z; zPos <= z; zPos++) {
                final int blockID = worldObj.getBlockId(xPos, y, zPos);
                final Block block = Block.blocksList[worldObj.getBlockId(xPos, y, zPos)];
                if ((block != null) && !block.isAirBlock(worldObj, xPos, y, zPos)) {
                    if (this.validBlockID(blockID)) {
                        return this.validateTop(x, y, z, increment);
                    } else {
                        return increment;
                    }
                }
            }
        }
        // Check outer layer
        for (int xPos = x - 1; xPos <= (x + 1); xPos++) {
            this.numBricks += this.checkBricks(xPos, y, z - 1);
            this.numBricks += this.checkBricks(xPos, y, z + 1);
        }
        for (int zPos = z; zPos <= z; zPos++) {
            this.numBricks += this.checkBricks(x - 1, y, zPos);
            this.numBricks += this.checkBricks(x + 1, y, zPos);
        }
        if (this.numBricks != 8) {
            return increment;
        }
        increment++;
        return this.recurseStructureUp(x, y + 1, z, increment);
    }

    /**
     * Scan down the structure for valid components.
     *
     * @param x        coordinate from center
     * @param y        coordinate from center
     * @param z        coordinate from center
     * @param count        current amount of blocks
     * @return block count
     */
    public final int recurseStructureDown(final int x, final int y, final int z, final int count) {
        this.numBricks = 0;
        int increment = count;
        // Check inside
        for (int xPos = x; xPos <= x; xPos++) {
            for (int zPos = z; zPos <= z; zPos++) {
                final int blockID = worldObj.getBlockId(xPos, y, zPos);
                final Block block = Block.blocksList[blockID];
                if ((block != null) && !block.isAirBlock(worldObj, xPos, y, zPos)) {
                    if (this.validBlockID(blockID)) {
                        return this.validateBottom(x, y, z, increment);
                    } else {
                        return increment;
                    }
                }
            }
        }
        // Check outer layer X
        for (int xPos = x - 1; xPos <= (x + 1); xPos++) {
            this.numBricks += this.checkBricks(xPos, y, z - 1);
            this.numBricks += this.checkBricks(xPos, y, z + 1);
        }
        // Check outer layer Z
        for (int zPos = z; zPos <= z; zPos++) {
            this.numBricks += this.checkBricks(x - 1, y, zPos);
            this.numBricks += this.checkBricks(x + 1, y, zPos);
        }
        if (this.numBricks != 8) {
            return increment;
        }
        increment++;
        return this.recurseStructureDown(x, y - 1, z, increment);
    }

    /**
     * Determine if layer is a valid top layer.
     *
     * @param x        coordinate from center
     * @param y        coordinate from center
     * @param z        coordinate from center
     * @param count        current amount of blocks
     * @return block count
     */
    public final int validateTop(final int x, final int y, final int z, final int count) {
        int topBricks = 0;
        for (int xPos = x - 1; xPos <= (x + 1); xPos++) {
            for (int zPos = z - 1; zPos <= (z + 1); zPos++) {
                if (this.validBlockID(worldObj.getBlockId(xPos, y, zPos)) && (worldObj.getBlockMetadata(xPos, y, zPos) >= 1)) {
                    topBricks += this.checkBricks(xPos, y, zPos);
                }
            }
        }
        this.structureHasTop = topBricks == 9;
        return count;
    }

    /**
     * Determine if layer is a valid bottom layer.
     *
     * @param x        coordinate from center
     * @param y        coordinate from center
     * @param z        coordinate from center
     * @param count        current amount of blocks
     * @return block count
     */
    public final int validateBottom(final int x, final int y, final int z, final int count) {
        int bottomBricks = 0;
        for (int xPos = x - 1; xPos <= (x + 1); xPos++) {
            for (int zPos = z - 1; zPos <= (z + 1); zPos++) {
                if (this.validBlockID(this.worldObj.getBlockId(xPos, y, zPos)) && (this.worldObj.getBlockMetadata(xPos, y, zPos) >= 1)) {
                    bottomBricks += this.checkBricks(xPos, y, zPos);
                }
            }
        }
        this.structureHasBottom = bottomBricks == 9;
        if (this.structureHasBottom) {
            this.centerPos = new CoordTuple(x, y + 1, z);
        }
        return count;
    }

    /**
     * Increments bricks, sets them as part of the structure.
     *
     * @param x        coordinate
     * @param y        coordinate
     * @param z        coordinate
     * @return int brick incement
     */
    final int checkBricks(final int x, final int y, final int z) {
        int tempBricks = 0;
        final int blockID = this.worldObj.getBlockId(x, y, z);
        if (this.validBlockID(blockID)) {
            final TileEntity te = this.worldObj.getBlockTileEntity(x, y, z);
            if (te == this) {
                tempBricks++;
            } else
                if (te instanceof TSMultiServantLogic) {
                    final TSMultiServantLogic servant = (TSMultiServantLogic) te;
                    if (servant.hasValidMaster()) {
                        if (servant.verifyMaster(this, this.worldObj, this.xCoord, this.yCoord, this.zCoord)) {
                            tempBricks++;
                        }
                    } else
                        if (servant.setMaster(this.xCoord, this.yCoord, this.zCoord)) {
                            tempBricks++;
                        }
                }
        }
        return tempBricks;
    }

    /**
     * Determine if block is a valid highoven component.
     *
     * @param blockID        the block id
     * @return Success
     */
    final boolean validBlockID(final int blockID) {
        return blockID == TSContent.highoven.blockID;
    }

    /* ==================== Fluid Handling ==================== */

    /**
     * Add molen metal fluidstack.
     * 
     * @param liquid
     *        the liquid
     * @param first
     *        the first
     * @return Success
     */
    final boolean addFluidToTank(final FluidStack liquid, final boolean first) {
        // TODO: Tank should only hold multiple fluids under certain special
        // circumstances ex water & steam, anything & slag
        this.needsUpdate = true;
        if (this.fluidlist.size() == 0) {
            this.fluidlist.add(liquid.copy());
            this.currentLiquid += liquid.amount;
            return true;
        } else {
            // if (liquid.fluidID != TSContent.steamFluid.getID())
            // return false;
            if ((liquid.amount + this.currentLiquid) > this.maxLiquid) {
                return false;
            }
            this.currentLiquid += liquid.amount;
            boolean added = false;
            for (int i = 0; i < this.fluidlist.size(); i++) {
                final FluidStack l = this.fluidlist.get(i);
                if (l.isFluidEqual(liquid)) {
                    l.amount += liquid.amount;
                    added = true;
                }
                if (l.amount <= 0) {
                    this.fluidlist.remove(l);
                    i--;
                }
            }
            if (!added) {
                if (first) {
                    this.fluidlist.add(0, liquid.copy());
                } else {
                    this.fluidlist.add(liquid.copy());
                }
            }
            return true;
        }
    }

    /**
     * Adds the item.
     * 
     * @param itemstack
     *        the ItemStack
     */
    final void addItem(final ItemStack itemstack) {
        boolean transferred = false;
        // If we have an output duct...
        if (this.outputDuct != null) {
            final TileEntity te = this.worldObj.getBlockTileEntity(this.outputDuct.x, this.outputDuct.y, this.outputDuct.z);
            if ((te != null) && (te instanceof HighOvenDuctLogic)) {
                transferred = this.sendItemToDuct((HighOvenDuctLogic) te, itemstack);
            } else {
                this.outputDuct = null; // If duct no longer exists, get rid of
                                        // it!
            }
        }
        // Dispense item if no duct is present
        if (!transferred) {
            this.dispenseItem(itemstack);
        }
    }

    /**
     * Send item to duct.
     *
     * @param duct        the duct
     * @param itemstack        the stack
     * @return true, if successful
     */
    final boolean sendItemToDuct(final HighOvenDuctLogic duct, final ItemStack itemstack) {
        boolean effective = false;
        ItemStack copystack = new ItemStack(itemstack.itemID, itemstack.stackSize, itemstack.getItemDamage());
        for (int slot = 0; slot < duct.getSizeInventory(); slot++) {
            final ItemStack getstack = duct.getStackInSlot(slot);

            if (duct.isItemValidForSlot(slot, copystack)) {
                boolean flag = false;
                if (copystack == null) {
                    break;
                }
                if (getstack == null) {
                    final int max = Math.min(itemstack.getMaxStackSize(), duct.getInventoryStackLimit());
                    if (max >= copystack.stackSize) {
                        duct.setInventorySlotContents(slot, copystack);
                        copystack = null;
                        flag = true;
                        effective = flag;
                    } else {
                        duct.setInventorySlotContents(slot, copystack.splitStack(max));
                        copystack = null;
                        flag = true;
                        effective = flag;
                    }
                } else
                    if (InventoryHelper.areItemStacksEqualItem(getstack, copystack)) {
                        final int max = Math.min(copystack.getMaxStackSize(), duct.getInventoryStackLimit());
                        if (max > getstack.stackSize) {
                            final int l = Math.min(copystack.stackSize, max - getstack.stackSize);
                            copystack.stackSize -= l;
                            getstack.stackSize += l;
                            if (copystack.stackSize < 1) {
                                copystack = null;
                            }
                            flag = l > 0;
                            effective = flag;
                        }
                    }
            }
            if (effective) {
                duct.onInventoryChanged();
            }
        }
        return effective;
    }

    /**
     * Gets the output duct.
     *
     * @return the output duct
     */
    public final CoordTuple getOutputDuct() {
        return this.outputDuct;
    }
    
    /**
     * Sets the output duct.
     *
     * @param duct the new output duct
     */
    public final void setOutputDuct(final CoordTuple duct) {
        this.outputDuct = duct;
    }
    
    /**
     * Dispense item.
     *
     * @param itemstack        the stack
     */
    final void dispenseItem(final ItemStack itemstack) {
        final BlockSourceImpl blocksourceimpl = new BlockSourceImpl(this.worldObj, this.xCoord, this.yCoord, this.zCoord);
        final IBehaviorDispenseItem ibehaviordispenseitem = (IBehaviorDispenseItem) this.dispenseBehavior.getObject(itemstack.getItem());

        if (ibehaviordispenseitem != IBehaviorDispenseItem.itemDispenseBehaviorProvider) {
            ibehaviordispenseitem.dispense(blocksourceimpl, itemstack);
        }
    }

    /**
     * Removes the from fluid list.
     *
     * @param fluidstack the fluidstack
     * @return true, if successful
     */
    public boolean removeFromFluidList(final FluidStack fluidstack) {
        return this.fluidlist.remove(fluidstack);
    }
    
    /**
     * Adds the to fluid list.
     *
     * @param fluidstack the fluidstack
     * @return true, if successful
     */
    public boolean addToFluidList(final FluidStack fluidstack) {
        return this.fluidlist.add(fluidstack);
    }
    
    /**
     * Adds the to fluid list.
     *
     * @param index the index
     * @param fluidstack the fluidstack
     */
    public void addToFluidList(final int index, final FluidStack fluidstack) {
        this.fluidlist.add(index,fluidstack);
    }
    
    /**
     * Get max liquid capacity.
     * 
     * @return the capacity
     */
    /*
     * (non-Javadoc)
     * 
     * @see net.minecraftforge.fluids.IFluidTank#getCapacity()
     */
    @Override
    public final int getCapacity() {
        return this.maxLiquid;
    }

    /**
     * Get current liquid amount.
     * 
     * @return the total liquid
     */
    public final int getTotalLiquid() {
        return this.currentLiquid;
    }

    /*
     * (non-Javadoc)
     * 
     * @see net.minecraftforge.fluids.IFluidTank#drain(int, boolean)
     */
    @Override
    public final FluidStack drain(final int maxDrain, final boolean doDrain) {
        if (this.fluidlist.size() == 0) {
            return null;
        }
        final FluidStack liquid = this.fluidlist.get(0);
        if (liquid != null) {
            if ((liquid.amount - maxDrain) <= 0) {
                final FluidStack liq = liquid.copy();
                if (doDrain) {
                    this.fluidlist.remove(liquid);
                    this.worldObj.markBlockForUpdate(this.xCoord, this.yCoord, this.zCoord);
                    this.currentLiquid = 0;
                    this.needsUpdate = true;
                }
                return liq;
            } else {
                if (doDrain && (maxDrain > 0)) {
                    liquid.amount -= maxDrain;
                    this.worldObj.markBlockForUpdate(this.xCoord, this.yCoord, this.zCoord);
                    this.currentLiquid -= maxDrain;
                    this.needsUpdate = true;
                }
                return new FluidStack(liquid.fluidID, maxDrain, liquid.tag);
            }
        } else {
            return new FluidStack(0, 0);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see net.minecraftforge.fluids.IFluidTank#fill(net.minecraftforge.fluids.
     * FluidStack, boolean)
     */
    @Override
    public final int fill(final FluidStack resource, final boolean doFill) {
        if (resource != null && this.currentLiquid < this.maxLiquid) {
            final boolean first = resource.getFluid() == FluidRegistry.WATER;
            if ((resource.amount + this.currentLiquid) > this.maxLiquid) {
                resource.amount = this.maxLiquid - this.currentLiquid;
            }
            final int amount = resource.amount;
            if ((amount > 0) && doFill) {
                this.addFluidToTank(resource, first);
                this.needsUpdate = true;
                this.worldObj.markBlockForRenderUpdate(this.xCoord, this.yCoord, this.zCoord);
            }
            return amount;
        } else {
            return 0;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see net.minecraftforge.fluids.IFluidTank#getFluid()
     */
    @Override
    public final FluidStack getFluid() {
        if (this.fluidlist.size() == 0) {
            return null;
        }
        return this.fluidlist.get(0);
    }

    /**
     * Gets the total fluid amount.
     * 
     * @return the total fluid amount
     */
    public final int getTotalFluidAmount() {
        if (this.fluidlist.size() == 0) {
            return this.currentLiquid;
        }

        int amt = 0;

        for (int i = 0; i < this.fluidlist.size(); i++) {
            final FluidStack l = this.fluidlist.get(i);
            amt += l.amount;
        }
        return amt;
    }

    /**
     * Gets the fill ratio.
     * 
     * @return the fill ratio
     */
    public final int getFillRatio() {
        return this.currentLiquid <= 0 ? 0 : this.maxLiquid / this.getTotalFluidAmount();
    }

    /*
     * (non-Javadoc)
     * 
     * @see net.minecraftforge.fluids.IFluidTank#getFluidAmount()
     */
    @Override
    public final int getFluidAmount() {
        return this.currentLiquid;
    }

    /*
     * (non-Javadoc)
     * 
     * @see net.minecraftforge.fluids.IFluidTank#getInfo()
     */
    @Override
    public final FluidTankInfo getInfo() {
        return new FluidTankInfo(this);
    }

    /**
     * Gets the fluidlist.
     *
     * @return the fluidlist
     */
    public final List<FluidStack> getFluidlist() {
        return this.fluidlist;
    }
    
    /**
     * Gets the multi tank info.
     * 
     * @return the multi tank info
     */
    public final FluidTankInfo[] getMultiTankInfo() {
        final FluidTankInfo[] info = new FluidTankInfo[this.fluidlist.size() + 1];
        for (int i = 0; i < this.fluidlist.size(); i++) {
            final FluidStack fluid = this.fluidlist.get(i);
            info[i] = new FluidTankInfo(fluid.copy(), fluid.amount);
        }
        info[this.fluidlist.size()] = new FluidTankInfo(null, this.maxLiquid - this.currentLiquid);
        return info;
    }

    /* ==================== NBT ==================== */

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.blocks.TSInventoryLogic#readFromNBT(net.minecraft.nbt.NBTTagCompound)
     */
    @Override
    public final void readFromNBT(final NBTTagCompound tags) {
        this.layers = tags.getInteger(TSRepo.NBTNames.layers);
        this.maxTemp = tags.getInteger(TSRepo.NBTNames.maxTemp);
        this.inventory = new ItemStack[4 + this.layers];

        final int[] duct = tags.getIntArray(TSRepo.NBTNames.outputDuct);
        if (duct.length > 2) {
            this.outputDuct = new CoordTuple(duct[0], duct[1], duct[2]);
        }

        super.readFromNBT(tags);
        this.setRSmode(tags.getBoolean(TSRepo.NBTNames.redstoneOn));
        this.internalTemp = tags.getInteger(TSRepo.NBTNames.internalTemp);
        this.isMeltingItems = tags.getBoolean(TSRepo.NBTNames.inUse);
        final int[] center = tags.getIntArray(TSRepo.NBTNames.centerPos);
        if (center.length > 2) {
            this.centerPos = new CoordTuple(center[0], center[1], center[2]);
        } else {
            this.centerPos = new CoordTuple(this.xCoord, this.yCoord, this.zCoord);
        }
        this.direction = tags.getByte(TSRepo.NBTNames.direction);
        this.setFuelBurnTime(tags.getInteger(TSRepo.NBTNames.useTime));
        this.setFuelHeatRate(tags.getInteger(TSRepo.NBTNames.fuelHeatRate));
        this.currentLiquid = tags.getInteger(TSRepo.NBTNames.currentLiquid);
        this.maxLiquid = tags.getInteger(TSRepo.NBTNames.maxLiquid);
        this.meltingTemps = tags.getIntArray(TSRepo.NBTNames.meltingTemps);
        this.activeTemps = tags.getIntArray(TSRepo.NBTNames.activeTemps);
        final NBTTagList liquidTag = tags.getTagList(TSRepo.NBTNames.liquids);
        this.fluidlist.clear();
        for (int iter = 0; iter < liquidTag.tagCount(); iter++) {
            final NBTTagCompound nbt = (NBTTagCompound) liquidTag.tagAt(iter);
            final FluidStack fluid = FluidStack.loadFluidStackFromNBT(nbt);
            if (fluid != null) {
                this.fluidlist.add(fluid);
            }
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * tsteelworks.lib.blocks.TSInventoryLogic#writeToNBT(net.minecraft.nbt.
     * NBTTagCompound)
     */
    @Override
    public final void writeToNBT(final NBTTagCompound tags) {
        super.writeToNBT(tags);
        tags.setBoolean(TSRepo.NBTNames.redstoneOn, this.redstoneActivated);
        tags.setInteger(TSRepo.NBTNames.internalTemp, this.internalTemp);
        tags.setBoolean(TSRepo.NBTNames.inUse, this.isMeltingItems);
        int[] duct = new int[3];
        if (this.outputDuct != null) {
            duct = new int[] { this.outputDuct.x, this.outputDuct.y, this.outputDuct.z };
        }
        tags.setIntArray(TSRepo.NBTNames.outputDuct, duct);

        int[] center = new int[3];
        if (this.centerPos == null) {
            center = new int[] { this.xCoord, this.yCoord, this.zCoord };
        } else {
            center = new int[] { this.centerPos.x, this.centerPos.y, this.centerPos.z };
        }
        tags.setIntArray(TSRepo.NBTNames.centerPos, center);
        tags.setByte(TSRepo.NBTNames.direction, this.direction);
        tags.setInteger(TSRepo.NBTNames.useTime, this.fuelBurnTime);
        tags.setInteger(TSRepo.NBTNames.fuelHeatRate, this.fuelHeatRate);
        tags.setInteger(TSRepo.NBTNames.currentLiquid, this.currentLiquid);
        tags.setInteger(TSRepo.NBTNames.maxLiquid, this.maxLiquid);
        tags.setInteger(TSRepo.NBTNames.layers, this.layers);
        tags.setInteger(TSRepo.NBTNames.maxTemp, this.maxTemp);
        tags.setIntArray(TSRepo.NBTNames.meltingTemps, this.meltingTemps);
        tags.setIntArray(TSRepo.NBTNames.activeTemps, this.activeTemps);
        final NBTTagList taglist = new NBTTagList();
        for (final FluidStack liquid : this.fluidlist) {
            final NBTTagCompound nbt = new NBTTagCompound();
            liquid.writeToNBT(nbt);
            taglist.appendTag(nbt);
        }
        tags.setTag(TSRepo.NBTNames.liquids, taglist);
    }

    /*
     * (non-Javadoc)
     * 
     * @see net.minecraft.tileentity.TileEntity#getDescriptionPacket()
     */
    @Override
    public final Packet getDescriptionPacket() {
        final NBTTagCompound tag = new NBTTagCompound();
        this.writeToNBT(tag);
        return new Packet132TileEntityData(this.xCoord, this.yCoord, this.zCoord, 1, tag);
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * net.minecraft.tileentity.TileEntity#onDataPacket(net.minecraft.network
     * .INetworkManager, net.minecraft.network.packet.Packet132TileEntityData)
     */
    @Override
    public final void onDataPacket(final INetworkManager net, final Packet132TileEntityData packet) {
        this.readFromNBT(packet.data);
        this.onInventoryChanged();
        this.worldObj.markBlockForRenderUpdate(this.xCoord, this.yCoord, this.zCoord);
        this.needsUpdate = true;
    }

    /* ==================== Other ==================== */

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.blocks.TSInventoryLogic#openChest()
     */
    @Override
    public void openChest() { }

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.blocks.TSInventoryLogic#closeChest()
     */
    @Override
    public void closeChest() { }

    /* =============== IMaster =============== */

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.IMasterLogic#getCoord()
     */
    @Override
    public final CoordTuple getCoord() {
        return new CoordTuple(this.xCoord, this.yCoord, this.zCoord);
    }

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.IMasterLogic#isValid()
     */
    @Override
    public final boolean isValid() {
        return this.validStructure;
    }

    /*
     * (non-Javadoc)
     * 
     * @see tsteelworks.lib.IMaster#getBlockId()
     */
    // @Override
    /**
     * Gets the block id.
     * 
     * @return the block id
     */
    public final int getBlockId() {
        return this.worldObj.getBlockId(this.xCoord, this.yCoord, this.zCoord);
    }
}
