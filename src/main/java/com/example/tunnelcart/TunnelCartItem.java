package com.example.tunnelcart;

import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.RailShape;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

public class TunnelCartItem extends Item {
    public TunnelCartItem(Settings settings) { super(settings); }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        BlockState state = world.getBlockState(pos);
        if (!state.isIn(BlockTags.RAILS) || !(state.getBlock() instanceof AbstractRailBlock rail)) return ActionResult.FAIL;
        RailShape shape = state.get(rail.getShapeProperty());
        Direction facing = ctx.getHorizontalPlayerFacing();
        Direction heading;
        if (shape == RailShape.NORTH_SOUTH) heading = facing.getAxis() == Direction.Axis.Z ? facing : Direction.SOUTH;
        else if (shape == RailShape.EAST_WEST) heading = facing.getAxis() == Direction.Axis.X ? facing : Direction.EAST;
        else return ActionResult.FAIL; // only straight flat rail
        if (!world.isClient) {
            TunnelCartEntity cart = new TunnelCartEntity(world, pos.getX() + 0.5, pos.getY() + 0.0625, pos.getZ() + 0.5);
            cart.setup(pos, heading);
            if (ctx.getStack().hasCustomName()) cart.setCustomName(ctx.getStack().getName());
            world.spawnEntity(cart);
            world.emitGameEvent(ctx.getPlayer(), GameEvent.ENTITY_PLACE, pos);
        }
        ctx.getStack().decrement(1);
        return ActionResult.success(world.isClient);
    }
}
