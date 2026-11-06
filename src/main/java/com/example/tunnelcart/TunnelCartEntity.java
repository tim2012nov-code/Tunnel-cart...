package com.example.tunnelcart;

import net.minecraft.block.*;
import net.minecraft.block.enums.RailShape;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.vehicle.StorageMinecartEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;

/**
 * Self-driving tunnel boring cart. Inventory (27 slots, like a chest minecart) holds
 * powered rails + cobbled deepslate (+ optional redstone blocks) and receives all mined loot.
 */
public class TunnelCartEntity extends StorageMinecartEntity {
    private static final TagKey<Block> C_ORES = TagKey.of(RegistryKeys.BLOCK, new Identifier("c", "ores"));
    private static final int FLAGS = Block.NOTIFY_LISTENERS; // no neighbour updates -> no fluid/gravity chaos

    private BlockPos origin = null;     // rail the cart was placed on (slice 0)
    private Direction heading = Direction.NORTH;
    private int built = 0;              // last fully finished slice
    private String issue = null;

    public TunnelCartEntity(EntityType<? extends TunnelCartEntity> type, World world) { super(type, world); }

    public TunnelCartEntity(World world, double x, double y, double z) {
        super(TunnelCartMod.TUNNEL_CART_ENTITY, x, y, z, world);
    }

    public void setup(BlockPos origin, Direction heading) {
        this.origin = origin.toImmutable();
        this.heading = heading;
        this.built = 0;
    }

    // ---------------------------------------------------------------- main loop
    @Override
    public void tick() {
        if (!getWorld().isClient && origin != null && getWorld() instanceof ServerWorld sw) {
            issue = null;
            double max = getMaxSpeed(); // includes High-Speed Rail's override
            double along = (getX() - origin.getX() - 0.5) * heading.getOffsetX()
                         + (getZ() - origin.getZ() - 0.5) * heading.getOffsetZ();

            int perTick = (int) Math.ceil(max);
            int target = (int) Math.floor(along) + Math.max(6, perTick * 3 + 3);
            int budget = perTick + 3;
            while (built < target && budget-- > 0) {
                if (!buildSlice(sw, built + 1)) break;
                built++;
            }

            // never outrun the finished tunnel
            double allowed = built - along - 1.0;
            double speed = Math.max(0.0, Math.min(max, allowed));
            setVelocity(heading.getOffsetX() * speed, getVelocity().y, heading.getOffsetZ() * speed);

            if (issue != null && speed < 0.05 && age % 40 == 0) {
                Text msg = Text.translatable("tunnelcart." + issue);
                for (var p : sw.getPlayers(pl -> pl.squaredDistanceTo(this) < 2500)) p.sendMessage(msg, true);
            }
        }
        super.tick();
    }

    // ---------------------------------------------------------------- one cross-section
    private boolean buildSlice(ServerWorld w, int k) {
        BlockPos c = origin.offset(heading, k);
        Direction side = heading.rotateYClockwise();
        if (!w.isChunkLoaded(c) || !w.isChunkLoaded(c.offset(side, 3)) || !w.isChunkLoaded(c.offset(side, -3))) {
            issue = "chunk"; return false;
        }

        // 0) cap fluid in the next slice's bore so it cannot flood this one
        BlockPos n = c.offset(heading);
        if (w.isChunkLoaded(n)) {
            for (int a = -1; a <= 1; a++) for (int h = 0; h <= 2; h++) {
                BlockPos p = n.offset(side, a).up(h);
                if (w.isOutOfHeightLimit(p)) continue;
                BlockState s = w.getBlockState(p);
                if (!s.getFluidState().isEmpty()) {
                    if (!take(Items.COBBLED_DEEPSLATE)) { issue = "need_cobbled"; return false; }
                    w.setBlockState(p, Blocks.COBBLED_DEEPSLATE.getDefaultState(), FLAGS);
                }
            }
        }

        // 1) shell (floor, ceiling, walls): seal caves / fluids / loose blocks / ores
        for (int a = -2; a <= 2; a++) for (int h = -1; h <= 3; h++) {
            if (Math.abs(a) <= 1 && h >= 0 && h <= 2) continue;
            BlockPos p = c.offset(side, a).up(h);
            if (w.isOutOfHeightLimit(p)) continue;
            BlockState s = w.getBlockState(p);
            if (s.isOf(Blocks.COBBLED_DEEPSLATE)) continue;
            boolean wanted = isWanted(s) || s.getBlock() instanceof FallingBlock;
            boolean weak = s.isAir() || !s.getFluidState().isEmpty() || !s.isFullCube(w, p);
            if (!(wanted || weak) || s.hasBlockEntity() || s.getHardness(w, p) < 0) continue;
            if (!take(Items.COBBLED_DEEPSLATE)) { issue = "need_cobbled"; return false; }
            if (wanted) collect(w, p, s);
            w.setBlockState(p, Blocks.COBBLED_DEEPSLATE.getDefaultState(), FLAGS);
        }

        // 2) bore 3x3 (rail cell handled below)
        for (int a = -1; a <= 1; a++) for (int h = 0; h <= 2; h++) {
            if (a == 0 && h == 0) continue;
            if (!clear(w, c.offset(side, a).up(h))) return false;
        }

        // 3) rail (+ optional redstone block under every 8th one so trailing carts get power)
        if (!clear(w, c)) return false;
        if (!take(Items.POWERED_RAIL)) { issue = "need_rail"; return false; }
        if (k % 8 == 0 && take(Items.REDSTONE_BLOCK)) {
            w.setBlockState(c.down(), Blocks.REDSTONE_BLOCK.getDefaultState(), FLAGS);
        }
        RailShape shape = heading.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
        w.setBlockState(c, Blocks.POWERED_RAIL.getDefaultState().with(PoweredRailBlock.SHAPE, shape), Block.NOTIFY_ALL);

        // 4) ores / gravel / clay in the 7x7 ring around the shell -> mined and replaced
        for (int a = -3; a <= 3; a++) for (int h = -2; h <= 4; h++) {
            if (Math.abs(a) <= 2 && h >= -1 && h <= 3) continue;
            BlockPos p = c.offset(side, a).up(h);
            if (w.isOutOfHeightLimit(p)) continue;
            BlockState s = w.getBlockState(p);
            if (!isWanted(s) || s.hasBlockEntity()) continue;
            if (!take(Items.COBBLED_DEEPSLATE)) continue;
            collect(w, p, s);
            w.setBlockState(p, Blocks.COBBLED_DEEPSLATE.getDefaultState(), FLAGS);
        }
        return true;
    }

    private boolean clear(ServerWorld w, BlockPos p) {
        if (w.isOutOfHeightLimit(p)) return true;
        BlockState s = w.getBlockState(p);
        if (s.isAir()) return true;
        if (s.getBlock() instanceof FluidBlock) { w.setBlockState(p, Blocks.AIR.getDefaultState(), FLAGS); return true; }
        if (s.hasBlockEntity() || s.getHardness(w, p) < 0) { issue = "blocked"; return false; }
        collect(w, p, s);
        w.setBlockState(p, Blocks.AIR.getDefaultState(), FLAGS);
        return true;
    }

    private static boolean isWanted(BlockState s) {
        return s.isOf(Blocks.GRAVEL) || s.isOf(Blocks.CLAY) || s.isIn(C_ORES)
                || s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES) || s.isIn(BlockTags.COPPER_ORES)
                || s.isIn(BlockTags.GOLD_ORES) || s.isIn(BlockTags.DIAMOND_ORES) || s.isIn(BlockTags.EMERALD_ORES)
                || s.isIn(BlockTags.LAPIS_ORES) || s.isIn(BlockTags.REDSTONE_ORES);
    }

    // ---------------------------------------------------------------- inventory helpers
    private void collect(ServerWorld w, BlockPos p, BlockState s) {
        List<ItemStack> drops = Block.getDroppedStacks(s, w, p, null, this, new ItemStack(Items.NETHERITE_PICKAXE));
        for (ItemStack d : drops) insert(d);
    }

    private void insert(ItemStack s) {
        for (int i = 0; i < size() && !s.isEmpty(); i++) {
            ItemStack t = getStack(i);
            if (!t.isEmpty() && ItemStack.canCombine(t, s)) {
                int m = Math.min(s.getCount(), t.getMaxCount() - t.getCount());
                if (m > 0) { t.increment(m); s.decrement(m); }
            }
        }
        for (int i = 0; i < size() && !s.isEmpty(); i++) {
            if (getStack(i).isEmpty()) { setStack(i, s.copy()); s.setCount(0); }
        }
        if (s.isEmpty()) return;
        // inventory full: void plain stone-like junk, drop everything valuable
        Item it = s.getItem();
        boolean junk = it.getClass() == BlockItem.class && it != Items.GRAVEL;
        if (!junk) dropStack(s);
    }

    private boolean take(Item item) {
        for (int i = 0; i < size(); i++) {
            ItemStack t = getStack(i);
            if (t.isOf(item)) { t.decrement(1); return true; }
        }
        return false;
    }

    // ---------------------------------------------------------------- minecart boilerplate
    @Override protected Item getItem() { return TunnelCartMod.TUNNEL_CART_ITEM; }
    @Override public int size() { return 27; }
    @Override public Type getMinecartType() { return Type.CHEST; }
    @Override public BlockState getDefaultContainedBlock() { return Blocks.BLAST_FURNACE.getDefaultState(); }
    @Override public int getDefaultBlockOffset() { return 8; }
    @Override public ScreenHandler getScreenHandler(int syncId, PlayerInventory inv) {
        return GenericContainerScreenHandler.createGeneric9x3(syncId, inv, this);
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (origin != null) {
            nbt.putInt("OX", origin.getX()); nbt.putInt("OY", origin.getY()); nbt.putInt("OZ", origin.getZ());
            nbt.putString("Heading", heading.getName());
            nbt.putInt("Built", built);
        }
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("OX")) {
            origin = new BlockPos(nbt.getInt("OX"), nbt.getInt("OY"), nbt.getInt("OZ"));
            Direction d = Direction.byName(nbt.getString("Heading"));
            heading = d != null ? d : Direction.NORTH;
            built = nbt.getInt("Built");
        }
    }
}
