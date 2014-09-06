/**
    Copyright (C) <2014> <coolAlias>

    This file is part of coolAlias' Zelda Sword Skills Minecraft Mod; as such,
    you can redistribute it and/or modify it under the terms of the GNU
    General Public License as published by the Free Software Foundation,
    either version 3 of the License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package zeldaswordskills.handler;

import net.minecraft.entity.EntityAgeable;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.EnumCreatureAttribute;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityGolem;
import net.minecraft.entity.monster.EntityWitch;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.entity.EntityEvent.EntityConstructing;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingJumpEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.player.EntityInteractEvent;
import net.minecraftforge.event.entity.player.PlayerFlyableFallEvent;
import zeldaswordskills.api.item.ArmorIndex;
import zeldaswordskills.entity.EntityGoron;
import zeldaswordskills.entity.EntityMaskTrader;
import zeldaswordskills.entity.ZSSEntityInfo;
import zeldaswordskills.entity.ZSSPlayerInfo;
import zeldaswordskills.entity.ZSSVillagerInfo;
import zeldaswordskills.entity.buff.Buff;
import zeldaswordskills.item.ItemCustomEgg;
import zeldaswordskills.item.ItemMask;
import zeldaswordskills.item.ItemTreasure.Treasures;
import zeldaswordskills.item.ZSSItems;
import zeldaswordskills.lib.Config;
import zeldaswordskills.network.SyncEntityInfoPacket;
import zeldaswordskills.skills.SkillBase;
import zeldaswordskills.skills.sword.LeapingBlow;
import cpw.mods.fml.common.network.PacketDispatcher;
import cpw.mods.fml.common.network.Player;

/**
 * 
 * Event handler for non-combat related entity events
 *
 */
public class ZSSEntityEvents
{
	/**
	 * NOTE: LivingFallEvent is not called in Creative mode, so must
	 * 		also listen for {@link PlayerFlyableFallEvent}
	 * Should receive canceled to make sure LeapingBlow triggers/deactivates
	 */
	@ForgeSubscribe(receiveCanceled=true)
	public void onFall(LivingFallEvent event) {
		if (event.entity instanceof EntityPlayer) {
			EntityPlayer player = (EntityPlayer) event.entity;
			ZSSPlayerInfo skills = ZSSPlayerInfo.get(player);
			if (skills.isSkillActive(SkillBase.leapingBlow)) {
				((LeapingBlow) skills.getPlayerSkill(SkillBase.leapingBlow)).onImpact(player, event.distance);
			}
			if (!event.isCanceled() && skills.reduceFallAmount > 0.0F) {
				event.distance -= skills.reduceFallAmount;
				skills.reduceFallAmount = 0.0F;
			}
		}
		if (!event.isCanceled() && event.entityLiving.getCurrentItemOrArmor(ArmorIndex.EQUIPPED_HELM) != null
				&& event.entityLiving.getCurrentItemOrArmor(ArmorIndex.EQUIPPED_HELM).getItem() == ZSSItems.maskBunny) {
			event.distance -= 5.0F;
		}
	}

	@ForgeSubscribe
	public void onCreativeFall(PlayerFlyableFallEvent event) {
		ZSSPlayerInfo skills = ZSSPlayerInfo.get(event.entityPlayer);
		if (skills.isSkillActive(SkillBase.leapingBlow)) {
			((LeapingBlow) skills.getPlayerSkill(SkillBase.leapingBlow)).onImpact(event.entityPlayer, event.distance);
		}
	}

	@ForgeSubscribe
	public void onJump(LivingJumpEvent event) {
		if (event.entityLiving.getHeldItem() != null && event.entityLiving.getHeldItem().getItem() == ZSSItems.rocsFeather) {
			event.entityLiving.motionY += (event.entityLiving.isSprinting() ? 0.30D : 0.15D);
		}
		if (event.entityLiving.getCurrentItemOrArmor(ArmorIndex.EQUIPPED_BOOTS) != null && event.entityLiving.getCurrentItemOrArmor(ArmorIndex.EQUIPPED_BOOTS).getItem() == ZSSItems.bootsPegasus) {
			event.entityLiving.motionY += 0.15D;
			if (event.entity instanceof EntityPlayer) {
				ZSSPlayerInfo.get((EntityPlayer) event.entity).reduceFallAmount += 1.0F;
			}
		}
		if (event.entityLiving.getCurrentItemOrArmor(ArmorIndex.EQUIPPED_HELM) != null && event.entityLiving.getCurrentItemOrArmor(ArmorIndex.EQUIPPED_HELM).getItem() == ZSSItems.maskBunny) {
			event.entityLiving.motionY += 0.30D;
			if (event.entity instanceof EntityPlayer) {
				ZSSPlayerInfo.get((EntityPlayer) event.entity).reduceFallAmount += 5.0F;
			}
		}
	}

	@ForgeSubscribe
	public void onInteract(EntityInteractEvent event) {
		if (event.target.getClass().isAssignableFrom(EntityVillager.class)) {
			EntityVillager villager = (EntityVillager) event.target;
			boolean flag2 = villager.getCustomNameTag().contains("Mask Salesman");
			if (!event.entityPlayer.worldObj.isRemote) {
				ItemStack stack = event.entityPlayer.getHeldItem();
				if (stack != null && stack.getItem() == ZSSItems.treasure && stack.getItemDamage() == Treasures.ZELDAS_LETTER.ordinal()) {
					if (flag2) {
						event.entityPlayer.addChatMessage(StatCollector.translateToLocal("chat.zss.treasure." + Treasures.ZELDAS_LETTER.name + ".for_me"));
					} else {
						event.entityPlayer.addChatMessage(StatCollector.translateToLocal("chat.zss.treasure." + Treasures.ZELDAS_LETTER.name + ".fail"));
					}
					flag2 = true;
				} else if (flag2) {
					event.entityPlayer.addChatMessage(StatCollector.translateToLocal("chat.zss.npc.mask_trader.closed." + event.entity.worldObj.rand.nextInt(4)));
				}
			}
			event.setCanceled(flag2);
		}
		if (!event.isCanceled() && (event.target instanceof EntityVillager || event.target instanceof EntityMaskTrader)) {
			ItemStack helm = event.entityPlayer.getCurrentArmor(ArmorIndex.WORN_HELM);
			if (helm != null && helm.getItem() instanceof ItemMask) {
				event.setCanceled(((ItemMask) helm.getItem()).onInteract(helm, event.entityPlayer, event.target));
			}
		}
		// allow custom spawn eggs to create child entities:
		if (!event.isCanceled() && event.target instanceof EntityAgeable) {
			ItemStack stack = event.entityPlayer.getHeldItem();
			if (stack != null && stack.getItem() instanceof ItemCustomEgg) {
				event.setCanceled(ItemCustomEgg.spawnChild(event.entity.worldObj, stack, event.entityPlayer, (EntityAgeable) event.target));
			}
		}
	}

	@ForgeSubscribe
	public void onLivingUpdate(LivingUpdateEvent event) {
		if (event.entity instanceof EntityPlayer) {
			EntityPlayer player = (EntityPlayer) event.entity;
			ZSSPlayerInfo.get(player).onUpdate();
			if (player.getHeldItem() != null && player.getHeldItem().getItem() == ZSSItems.rocsFeather && player.motionY < -0.25D) {
				player.motionY = -0.25D;
				player.fallDistance = 0.0F;
			}
		}
		if (event.entity instanceof EntityLivingBase) {
			ZSSEntityInfo.get((EntityLivingBase) event.entity).onUpdate();
		}
		if (event.entity instanceof EntityVillager) {
			ZSSVillagerInfo.get((EntityVillager) event.entity).onUpdate();
		}
	}

	@ForgeSubscribe
	public void onEntityJoinWorld(EntityJoinWorldEvent event) {
		if (!event.entity.worldObj.isRemote) {
			if (event.entity instanceof EntityPlayer) {
				EntityPlayer player = (EntityPlayer) event.entity;
				ZSSPlayerInfo.loadProxyData(player);
				PacketDispatcher.sendPacketToPlayer(new SyncEntityInfoPacket(ZSSEntityInfo.get(player)).makePacket(), (Player) player);
				ZSSPlayerInfo.get(player).verifyStartingGear();
				ZSSPlayerInfo.get(player).verifyMaxHealth();
			} else if (event.entity.getClass().isAssignableFrom(EntityVillager.class)) {
				EntityGoron.doVillagerSpawn((EntityVillager) event.entity, event.entity.worldObj);
			}

			if (!Config.areVanillaBuffsDisabled() && event.entity instanceof EntityLivingBase) {
				initBuffs((EntityLivingBase) event.entity);
			}
		}
	}

	@ForgeSubscribe
	public void onEntityConstructing(EntityConstructing event) {
		if (event.entity instanceof EntityLivingBase && ZSSEntityInfo.get((EntityLivingBase) event.entity) == null) {
			ZSSEntityInfo.register((EntityLivingBase) event.entity);
		}
		if (event.entity instanceof EntityVillager && ZSSVillagerInfo.get((EntityVillager) event.entity) == null) {
			ZSSVillagerInfo.register((EntityVillager) event.entity);
		}
		if (event.entity instanceof EntityPlayer && ZSSPlayerInfo.get((EntityPlayer) event.entity) == null) {
			ZSSPlayerInfo.register((EntityPlayer) event.entity);
		}
	}

	/**
	 * Applies permanent buffs / debuffs to vanilla mobs
	 */
	private void initBuffs(EntityLivingBase entity) {
		if (!ZSSEntityInfo.get(entity).getActiveBuffsMap().isEmpty()) {
			return;
		}
		// double damage from cold effects, highly resistant to fire damage
		if (entity.isImmuneToFire()) {
			ZSSEntityInfo.get(entity).applyBuff(Buff.RESIST_FIRE, Integer.MAX_VALUE, 75);
			ZSSEntityInfo.get(entity).applyBuff(Buff.WEAKNESS_COLD, Integer.MAX_VALUE, 100);
		}
		if (entity.getCreatureAttribute() == EnumCreatureAttribute.UNDEAD) {
			if (!entity.isImmuneToFire()) {
				ZSSEntityInfo.get(entity).applyBuff(Buff.WEAKNESS_FIRE, Integer.MAX_VALUE, 50);
			}
			ZSSEntityInfo.get(entity).applyBuff(Buff.WEAKNESS_HOLY, Integer.MAX_VALUE, 300);
			ZSSEntityInfo.get(entity).applyBuff(Buff.RESIST_COLD, Integer.MAX_VALUE, 50);
			ZSSEntityInfo.get(entity).applyBuff(Buff.RESIST_STUN, Integer.MAX_VALUE, 50);
		}
		if (entity instanceof EntityGolem) {
			ZSSEntityInfo.get(entity).applyBuff(Buff.RESIST_COLD, Integer.MAX_VALUE, 100);
			ZSSEntityInfo.get(entity).applyBuff(Buff.RESIST_STUN, Integer.MAX_VALUE, 100);
		}
		if (entity instanceof EntityWitch) {
			ZSSEntityInfo.get(entity).applyBuff(Buff.RESIST_MAGIC, Integer.MAX_VALUE, 75);
		}
		if (entity instanceof EntityWither) {
			ZSSEntityInfo.get(entity).removeBuff(Buff.WEAKNESS_COLD);
		}
	}
}
