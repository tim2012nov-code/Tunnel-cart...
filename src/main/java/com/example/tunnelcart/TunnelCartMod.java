package com.example.tunnelcart;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class TunnelCartMod implements ModInitializer {
    public static final String ID = "tunnelcart";

    public static final EntityType<TunnelCartEntity> TUNNEL_CART_ENTITY = Registry.register(
            Registries.ENTITY_TYPE, new Identifier(ID, "tunnel_cart"),
            EntityType.Builder.<TunnelCartEntity>create(TunnelCartEntity::new, SpawnGroup.MISC)
                    .setDimensions(0.98f, 0.7f).maxTrackingRange(8).trackingTickInterval(1)
                    .build("tunnel_cart"));

    public static final Item TUNNEL_CART_ITEM = Registry.register(
            Registries.ITEM, new Identifier(ID, "tunnel_cart"),
            new TunnelCartItem(new Item.Settings().maxCount(1)));

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(e -> e.add(TUNNEL_CART_ITEM));
    }
}
