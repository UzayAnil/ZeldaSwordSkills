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

package zeldaswordskills.entity;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeInstance;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraftforge.common.IExtendedEntityProperties;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

import org.apache.commons.lang3.ArrayUtils;

import zeldaswordskills.CommonProxy;
import zeldaswordskills.api.item.ArmorIndex;
import zeldaswordskills.client.ZSSKeyHandler;
import zeldaswordskills.handler.ZSSCombatEvents;
import zeldaswordskills.item.ItemArmorBoots;
import zeldaswordskills.item.ItemHeroBow;
import zeldaswordskills.item.ItemMask;
import zeldaswordskills.item.ItemZeldaShield;
import zeldaswordskills.item.ZSSItems;
import zeldaswordskills.lib.Config;
import zeldaswordskills.network.AttackBlockedPacket;
import zeldaswordskills.network.SetNockedArrowPacket;
import zeldaswordskills.network.SpawnNayruParticlesPacket;
import zeldaswordskills.network.SyncPlayerInfoPacket;
import zeldaswordskills.skills.ICombo;
import zeldaswordskills.skills.ILockOnTarget;
import zeldaswordskills.skills.SkillActive;
import zeldaswordskills.skills.SkillBase;
import zeldaswordskills.util.WorldUtils;
import cpw.mods.fml.common.network.PacketDispatcher;
import cpw.mods.fml.common.network.Player;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

public final class ZSSPlayerInfo implements IExtendedEntityProperties
{
	private static final String EXT_PROP_NAME = "ZSSPlayerInfo";

	private final EntityPlayer player;

	/** Special block timer for shields; player cannot block while this is greater than zero */
	private int blockTime = 0;

	public static enum Stats {
		/** Number of secret rooms this player has opened */
		STAT_SECRET_ROOMS,
		/** Bit storage for each type of boss room opened */
		STAT_BOSS_ROOMS
	};

	/** ZSS player statistics */
	private final Map<Stats, Integer> playerStats = new EnumMap<Stats, Integer>(Stats.class);

	private static final UUID hardcoreHeartUUID = UUID.fromString("83E54288-6BE2-4398-A880-957654F515AB");
	/** Max health modifier for players with Hardcore Zelda Fan mode enabled */
	private static final AttributeModifier hardcoreHeartModifier = (new AttributeModifier(hardcoreHeartUUID, "Hardcore Zelda Hearts", -14.0D, 0)).setSaved(true);

	/** Set to true when the player receives the bonus starting gear */
	private byte receivedGear = 0;

	/** Flags for worn gear and other things; none of these should be saved */
	private byte flags = 0;

	public static final byte
	/** Flag for whether the player is wearing special boots */
	IS_WEARING_BOOTS = 1,
	/** Flag for whether the player is wearing special headgear */
	IS_WEARING_HELM = 2,
	/** Flag for whether Nayru's Love is currently active */
	IS_NAYRU_ACTIVE = 4,
	/** Flag for whether the player's lateral movement should be increased while in the air */
	MOBILITY = 8;

	/** Last equipped special boots */
	private int lastBootsID = -1;

	/** Last equipped special helm (used for masks) */
	private int lastHelmID = -1;

	/** Current amount of time hovered */
	public int hoverTime = 0;

	/** Stores information on the player's skills */
	private final Map<Byte, SkillBase> skills;

	/**
	 * Currently active skill that {@link SkillActive#hasAnimation() has an animation};
	 * it may or may not currently be {@link SkillActive#isAnimating() animating}
	 */
	@SideOnly(Side.CLIENT)
	private SkillActive animatingSkill;

	/** Currently active skills */
	private final List<SkillActive> activeSkills = new LinkedList<SkillActive>();

	/** Number of Super Spin Attack orbs received from the Great Fairy: used to prevent exploits */
	private int fairySpinOrbsReceived = 0;

	/** The last mask borrowed from the Happy Mask Salesman */
	private Item borrowedMask = null;

	/** Current stage in the Mask trading sequence */
	private int maskStage = 0;

	/** [Hero's Bow] Stores the currently nocked arrow in order to avoid the graphical glitch caused by writing to the stack's NBT */
	private ItemStack arrowStack = null;

	/** [Hero's Bow] Part of the graphical glitch fix: Whether the player retrieved a bomb arrow via getAutoBombArrow */
	public boolean hasAutoBombArrow = false;

	/** Reduces fall damage next impact; used for Rising Cut */
	public float reduceFallAmount = 0.0F;

	public ZSSPlayerInfo(EntityPlayer player) {
		this.player = player;
		skills = new HashMap<Byte, SkillBase>(SkillBase.getNumSkills());
		initStats();
	}

	/** Whether the player is able to block at this time (block timer is zero) */
	public boolean canBlock() {
		return blockTime == 0;
	}

	/**
	 * Sets the player's block timer, clears the item in use and adds exhaustion upon blocking an attack
	 * @param damage only used server side to calculate exhaustion: 0.3F * damage
	 */
	public void onAttackBlocked(ItemStack shield, float damage) {
		ZSSCombatEvents.setPlayerAttackTime(player);
		blockTime = (shield.getItem() instanceof ItemZeldaShield ? ((ItemZeldaShield) shield.getItem()).getRecoveryTime() : 20);
		player.clearItemInUse();
		if (!player.worldObj.isRemote) {
			PacketDispatcher.sendPacketToPlayer(new AttackBlockedPacket(shield).makePacket(), (Player) player);
			player.addExhaustion(0.3F * damage);
		}
	}

	private void initStats() {
		for (Stats stat : Stats.values()) {
			playerStats.put(stat, 0);
		}
	}

	/** Gets the player's current stat value */
	public int getStat(Stats stat) {
		return playerStats.get(stat);
	}

	/** Adds the value to the player's stats */
	public void addStat(Stats stat, int value) {
		int i = playerStats.remove(stat);
		switch(stat) {
		case STAT_BOSS_ROOMS: playerStats.put(stat, i | value); break;
		default: playerStats.put(stat, i + value);
		}
	}

	/**
	 * Resets all data related to skills
	 */
	public void resetSkills() {
		// need level zero skills for validation, specifically for attribute-affecting skills
		for (SkillBase skill : SkillBase.getSkills()) {
			skills.put(skill.getId(), skill.newInstance());
		}
		validateSkills();
		skills.clear();
		fairySpinOrbsReceived = 0;
		PacketDispatcher.sendPacketToPlayer(new SyncPlayerInfoPacket(this).setReset().makePacket(), (Player) player);
	}

	/**
	 * Validates each skill upon player respawn, ensuring all bonuses are correct
	 */
	public final void validateSkills() {
		for (SkillBase skill : skills.values()) {
			skill.validateSkill(player);
		}
	}

	/**
	 * Applies hardcore Zelda fan heart modifier, if appropriate
	 */
	public void verifyMaxHealth() {
		AttributeInstance attributeinstance = player.getEntityAttribute(SharedMonsterAttributes.maxHealth);
		if (attributeinstance.getModifier(hardcoreHeartUUID) != null) {
			attributeinstance.removeModifier(hardcoreHeartModifier);
		}
		if (Config.isHardcoreZeldaFan()) {
			attributeinstance.applyModifier(hardcoreHeartModifier);
		}
		if (player.getHealth() > player.getMaxHealth()) {
			player.setHealth(player.getMaxHealth());
		}
	}

	/**
	 * If player has not received starting gear, it is provided
	 */
	public void verifyStartingGear() {
		if ((receivedGear & 1) == 0 && ZSSItems.grantBonusGear(player)) {
			receivedGear |= 1;
		}
	}

	/**
	 * Sets a bitflag to true or false; see flags for valid flag ids
	 */
	public void setFlag(byte flag, boolean value) {
		if (value) {
			flags |= flag;
		} else {
			flags &= ~flag;
		}
	}

	/** Returns true if the specified flag is set */
	public boolean getFlag(byte flag) {
		return (flags & flag) == flag;
	}

	/** Activates Nayru's Love effect; should call on both client and server */
	public void activateNayru() {
		setFlag(IS_NAYRU_ACTIVE, true);
	}

	/** Returns whether the player is currently under the effects of Nayru's Love */
	public boolean isNayruActive() {
		return getFlag(IS_NAYRU_ACTIVE);
	}

	/**
	 * Checks whether player is wearing special boots based on currently equipped gear
	 * and applies / removes modifiers as appropriate
	 */
	public void setWearingBoots() {
		ItemStack boots = player.getCurrentArmor(ArmorIndex.WORN_BOOTS);
		setFlag(IS_WEARING_BOOTS, (boots != null && boots.getItem() instanceof ItemArmorBoots));
		lastBootsID = (boots != null ? boots.itemID : -1);
		ItemArmorBoots.applyAttributeModifiers(boots, player);
		if (!getFlag(IS_WEARING_BOOTS) && getFlag(IS_WEARING_HELM)) {
			ItemMask.applyAttributeModifiers(player.getCurrentArmor(ArmorIndex.WORN_HELM), player);
		}
	}

	/**
	 * Checks whether player is wearing a special helm based on currently equipped gear
	 * and applies / removes modifiers as appropriate
	 */
	public void setWearingHelm() {
		ItemStack helm = player.getCurrentArmor(ArmorIndex.WORN_HELM);
		setFlag(IS_WEARING_HELM, (helm != null && helm.getItem() instanceof ItemMask));
		lastHelmID = (helm != null ? helm.itemID : -1);
		ItemMask.applyAttributeModifiers(helm, player);
		if (!getFlag(IS_WEARING_HELM) && getFlag(IS_WEARING_BOOTS)) {
			ItemArmorBoots.applyAttributeModifiers(player.getCurrentArmor(ArmorIndex.WORN_BOOTS), player);
		}
	}

	/**
	 * Returns true if this player meets the skill requirements to receive a Super
	 * Spin Attack orb from the Great Fairy
	 */
	public boolean canReceiveFairyOrb() {
		return (getSkillLevel(SkillBase.spinAttack) > getSkillLevel(SkillBase.superSpinAttack) &&
				getSkillLevel(SkillBase.superSpinAttack) >= fairySpinOrbsReceived &&
				getSkillLevel(SkillBase.bonusHeart) >= (fairySpinOrbsReceived * Config.getMaxBonusHearts() / 5));
	}

	/** Returns whether the player has already received all 5 Super Spin Attack orbs */
	public boolean hasReceivedAllOrbs() {
		return (fairySpinOrbsReceived == SkillBase.MAX_LEVEL);
	}

	/** Increments the number of Super Spin Attack orbs received, returning true if it's the last one */
	public boolean receiveFairyOrb() {
		return (++fairySpinOrbsReceived == SkillBase.MAX_LEVEL);
	}

	/** Returns the last mask borrowed, or null if no mask has been borrowed */
	public Item getBorrowedMask() {
		return borrowedMask;
	}

	/** Sets the mask that the player has borrowed */
	public void setBorrowedMask(Item item) {
		borrowedMask = item;
	}

	/**
	 * The player's current progress along the mask quest (% 3 gives stage):
	 * 0 - can get next mask, 1 - need to sell mask, 2 - need to pay for mask
	 */
	public int getCurrentMaskStage() {
		return maskStage;
	}

	/** Increments the mask quest stage by one */
	public void completeCurrentMaskStage() {
		++maskStage;
	}

	/**
	 * Returns the currently nocked arrow for the Hero's Bow, possibly null
	 */
	public ItemStack getNockedArrow() {
		return arrowStack;
	}

	/**
	 * Marks this arrow as nocked in the Hero's Bow
	 * @param stack The current arrow or null if empty
	 */
	public void setNockedArrow(ItemStack stack) {
		arrowStack = stack;
		if (!player.worldObj.isRemote) {
			PacketDispatcher.sendPacketToPlayer(new SetNockedArrowPacket(stack).makePacket(), (Player) player);
		}
	}

	/** Returns true if the player has at least one level in the specified skill */
	public boolean hasSkill(SkillBase skill) {
		return hasSkill(skill.getId());
	}

	/** Returns true if the player has at least one level in the specified skill (of any class) */
	public boolean hasSkill(byte id) {
		return getSkillLevel(id) > 0;
	}

	/** Returns the player's skill level for given skill, or 0 if the player doesn't have that skill */
	public byte getSkillLevel(SkillBase skill) {
		return getSkillLevel(skill.getId());
	}

	/** Returns the player's skill level for given skill, or 0 if the player doesn't have that skill */
	public byte getSkillLevel(byte id) {
		return (skills.containsKey(id) ? skills.get(id).getLevel() : 0);
	}

	/**
	 * Returns true if the player has the skill and the skill is currently active
	 */
	public boolean isSkillActive(SkillBase skill) {
		for (SkillActive active : activeSkills) {
			if (active.getId() == skill.getId()) {
				return active.isActive();
			}
		}
		return false;
	}

	/**
	 * Returns the {@link #animatingSkill}, which may be null
	 */
	@SideOnly(Side.CLIENT)
	public SkillActive getCurrentlyAnimatingSkill() {
		return animatingSkill;
	}

	/**
	 * This method is called automatically from {@link #onSkillActivated} for each skill activated.
	 * @param skill If this skill {@link SkillActive#hasAnimation has an animation}, it will be set
	 * 				as the currently animating skill.
	 */
	@SideOnly(Side.CLIENT)
	public void setCurrentlyAnimatingSkill(SkillActive skill) {
		animatingSkill = (skill == null || skill.hasAnimation() ? skill : animatingSkill);
	}

	/**
	 * Returns whether key/mouse input and skill interactions are currently allowed,
	 * i.e. the {@link #animatingSkill} is either null or not currently animating
	 */
	@SideOnly(Side.CLIENT)
	public boolean canInteract() {
		// don't set the current skill to null just yet if it is still animating
		// this allows skills to prevent key/mouse input without having to be 'active'
		if (animatingSkill != null && !animatingSkill.isActive() && !animatingSkill.isAnimating()) {
			animatingSkill = null;
		}
		return animatingSkill == null || !animatingSkill.isAnimating();
	}

	/**
	 * Call when a key is pressed to pass the key press to the player's skills'
	 * {@link SkillActive#keyPressed keyPressed} method, but only if the skill returns
	 * true from {@link SkillActive#isKeyListener isKeyListener} for the key pressed.
	 * The first skill to return true from keyPressed precludes any remaining skills
	 * from receiving the key press.
	 * @return	True if a listening skill's {@link SkillActive#keyPressed} signals that the key press was handled
	 */
	@SideOnly(Side.CLIENT)
	public boolean onKeyPressed(Minecraft mc, KeyBinding key) {
		for (SkillBase skill : skills.values()) {
			if (skill instanceof SkillActive && ((SkillActive) skill).isKeyListener(mc, key)) {
				if (((SkillActive) skill).keyPressed(mc, key, player)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Called from LivingAttackEvent to trigger {@link SkillActive#onBeingAttacked} for each
	 * currently active skill, potentially canceling the event. If the event is canceled, it
	 * returns immediately without processing any remaining active skills.
	 */
	public void onBeingAttacked(LivingAttackEvent event) {
		for (SkillActive skill : activeSkills) {
			if (skill.isActive() && skill.onBeingAttacked(player, event.source)) {
				event.setCanceled(true);
				return;
			}
		}
	}

	/**
	 * Called from LivingHurtEvent to trigger {@link SkillActive#postImpact} for each
	 * currently active skill, potentially altering the value of event.ammount, as
	 * well as calling {@link ICombo#onHurtTarget onHurtTarget} for the current ICombo.
	 */
	public void onPostImpact(LivingHurtEvent event) {
		for (SkillActive skill : activeSkills) {
			if (skill.isActive()) {
				event.ammount = skill.postImpact(player, event.entityLiving, event.ammount);
			}
		}
		// combo gets updated last, after all damage modifications are completed
		if (getComboSkill() != null) {
			getComboSkill().onHurtTarget(player, event);
		}
	}

	/**
	 * Returns a SkillActive version of the player's actual skill instance,
	 * or null if the player doesn't have the skill or it is not the correct type
	 */
	public SkillActive getActiveSkill(SkillBase skill) {
		SkillBase active = getPlayerSkill(skill.getId());
		return (active instanceof SkillActive ? (SkillActive) active : null);
	}

	/** Returns the player's actual skill instance or null if the player doesn't have the skill */
	public SkillBase getPlayerSkill(SkillBase skill) {
		return getPlayerSkill(skill.getId());
	}

	/** Returns the player's actual skill instance or null if the player doesn't have the skill */
	public SkillBase getPlayerSkill(byte id) {
		return (skills.containsKey(id) ? skills.get(id) : null);
	}

	/**
	 * Returns first ICombo from a currently active skill, if any; ICombo may or may not be in progress
	 */
	public ICombo getComboSkill() {
		SkillBase skill = getPlayerSkill(SkillBase.swordBasic);
		if (skill != null && (((ICombo) skill).getCombo() != null || ((SkillActive) skill).isActive())) {
			return (ICombo) skill;
		}
		return null;
	}

	/** Returns player's ILockOnTarget skill, if any */
	public ILockOnTarget getTargetingSkill() {
		return (ILockOnTarget) getPlayerSkill(SkillBase.swordBasic);
	}

	/** Grants a skill with target level of current skill level plus one */
	public boolean grantSkill(SkillBase skill) {
		return grantSkill(skill.getId(), (byte)(getSkillLevel(skill) + 1));
	}

	/**
	 * Grants skill to player if player meets the requirements; returns true if skill learned
	 */
	public boolean grantSkill(byte id, byte targetLevel) {
		SkillBase skill = skills.containsKey(id) ? (SkillBase) skills.get(id) : SkillBase.getNewSkillInstance(id);
		if (skill.grantSkill(player, targetLevel)) {
			skills.put(id, skill);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Call after activating any skill to ensure it is added to the list of 
	 * current active skills and set as the currently animating skill
	 */
	private void onSkillActivated(World world, SkillActive skill) {
		if (skill.isActive()) {
			activeSkills.add(skill);
		}
		if (world.isRemote) {
			setCurrentlyAnimatingSkill(skill);
		}
	}

	/**
	 * Returns true if the player has this skill and {@link SkillActive#activate} returns true
	 */
	public boolean activateSkill(World world, SkillBase skill) {
		return activateSkill(world, skill.getId());
	}

	/**
	 * Returns true if the player has this skill and {@link SkillActive#activate} returns true
	 */
	public boolean activateSkill(World world, byte id) {
		SkillBase skill = skills.get(id);
		if (skill instanceof SkillActive && ((SkillActive) skill).activate(world, player)) {
			onSkillActivated(world, (SkillActive) skill);
			return true;
		}
		return false;
	}

	/**
	 * Returns true if the player has this skill and {@link SkillActive#trigger} returns true
	 */
	public boolean triggerSkill(World world, SkillBase skill) {
		return triggerSkill(world, skill.getId());
	}

	/**
	 * Returns true if the player has this skill and {@link SkillActive#trigger} returns true
	 */
	public boolean triggerSkill(World world, byte id) {
		SkillBase skill = skills.get(id);
		if (skill instanceof SkillActive && ((SkillActive) skill).trigger(world, player, true)) {
			onSkillActivated(world, (SkillActive) skill);
			return true;
		}
		return false;
	}

	/**
	 * Reads a SkillBase from stream and updates the local skills map
	 * Called client side only for synchronizing a skill with the server version.
	 */
	@SideOnly(Side.CLIENT)
	public void syncClientSideSkill(byte id, NBTTagCompound compound) {
		if (SkillBase.doesSkillExist(id)) {
			skills.put(id, SkillBase.getNewSkillInstance(id).loadFromNBT(compound));
		}
	}

	/**
	 * This method should be called every update tick; currently called from LivingUpdateEvent
	 */
	public void onUpdate() {
		if (blockTime > 0) {
			--blockTime;
		}
		if (getFlag(IS_NAYRU_ACTIVE)) {
			updateNayru();
		}
		if (getFlag(IS_WEARING_BOOTS) && (player.getCurrentArmor(ArmorIndex.WORN_BOOTS) == null
				|| player.getCurrentArmor(ArmorIndex.WORN_BOOTS).itemID != lastBootsID)) {
			setWearingBoots();
		}
		if (getFlag(IS_WEARING_HELM) && (player.getCurrentArmor(ArmorIndex.WORN_HELM) == null
				|| player.getCurrentArmor(ArmorIndex.WORN_HELM).itemID != lastHelmID)) {
			setWearingHelm();
		}
		if (getFlag(MOBILITY) && !player.onGround && Math.abs(player.motionY) > 0.05D
				&& !player.capabilities.isFlying && player.worldObj.getWorldTime() % 2 == 0) {
			player.motionX *= 1.15D;
			player.motionZ *= 1.15D;
		}
		if (hasAutoBombArrow && (player.getItemInUse() == null || !(player.getItemInUse().getItem() instanceof ItemHeroBow))) {
			hasAutoBombArrow = false;
		}
		// let skill's update tick occur first
		for (SkillBase skill : skills.values()) {
			skill.onUpdate(player);
		} // and then remove from active list if no longer active
		// must use iterators to avoid concurrent modification exceptions to list
		Iterator<SkillActive> iterator = activeSkills.iterator();
		while (iterator.hasNext()) {
			SkillActive skill = iterator.next();
			if (!skill.isActive()) {
				iterator.remove();
			}
		}
		if (player.worldObj.isRemote) {
			if (ZSSKeyHandler.keys[ZSSKeyHandler.KEY_BLOCK].pressed && isSkillActive(SkillBase.swordBasic) && player.getHeldItem() != null) {
				Minecraft.getMinecraft().playerController.sendUseItem(player, player.worldObj, player.getHeldItem());
			}
		}
	}

	/**
	 * Updates effects of Nayru's Love
	 */
	private void updateNayru() {
		player.hurtResistantTime = player.maxHurtResistantTime;
		if (player.ticksExisted % 16 == 0) {
			player.addExhaustion(3.0F);
		}
		if (player.getFoodStats().getFoodLevel() == 0) {
			setFlag(IS_NAYRU_ACTIVE, false);
		} else {
			WorldUtils.sendPacketToAllAround(new SpawnNayruParticlesPacket(player).makePacket(), player.worldObj, player, 4096.0D);
		}
	}

	/** Used to register these extended properties for the player during EntityConstructing event */
	public static final void register(EntityPlayer player) {
		player.registerExtendedProperties(EXT_PROP_NAME, new ZSSPlayerInfo(player));
	}

	/** Returns ExtendedPlayer properties for player */
	public static final ZSSPlayerInfo get(EntityPlayer player) {
		return (ZSSPlayerInfo) player.getExtendedProperties(EXT_PROP_NAME);
	}

	/** Makes it look nicer in the methods save/loadProxyData */
	private static final String getSaveKey(EntityPlayer player) {
		return player.username + ":" + EXT_PROP_NAME;
	}

	/**
	 * Does everything I did in onLivingDeathEvent and it's static,
	 * so you now only need to use the following in the above event:
	 * ExtendedPlayer.saveProxyData((EntityPlayer) event.entity));
	 */
	public static final void saveProxyData(EntityPlayer player) {
		NBTTagCompound tag = new NBTTagCompound();
		ZSSPlayerInfo.get(player).saveNBTData(tag);
		CommonProxy.storeEntityData(getSaveKey(player), tag);
	}

	/**
	 * This cleans up the onEntityJoinWorld event by replacing most of the code
	 * with a single line: ExtendedPlayer.loadProxyData((EntityPlayer) event.entity));
	 */
	public static final void loadProxyData(EntityPlayer player) {
		ZSSPlayerInfo info = ZSSPlayerInfo.get(player);
		NBTTagCompound tag = CommonProxy.getEntityData(getSaveKey(player));
		if (tag != null) {
			info.loadNBTData(tag);
		}
		info.validateSkills();
		PacketDispatcher.sendPacketToPlayer(new SyncPlayerInfoPacket(info).makePacket(), (Player) player);
	}

	@Override
	public void saveNBTData(NBTTagCompound compound) {
		NBTTagList taglist = new NBTTagList();
		for (SkillBase skill : skills.values()) {
			NBTTagCompound skillTag = new NBTTagCompound();
			skill.writeToNBT(skillTag);
			taglist.appendTag(skillTag);
		}
		compound.setTag("ZeldaSwordSkills", taglist);
		compound.setIntArray("zssStats", ArrayUtils.toPrimitive(playerStats.values().toArray(new Integer[playerStats.size()])));
		compound.setByte("ZSSGearReceived", receivedGear);
		compound.setInteger("lastBoots", lastBootsID);
		compound.setInteger("lastHelm", lastHelmID);
		compound.setInteger("fairySpinOrbsReceived", fairySpinOrbsReceived);
		compound.setInteger("borrowedMask", borrowedMask != null ? borrowedMask.itemID : -1);
		compound.setInteger("maskStage", maskStage);
	}

	@Override
	public void loadNBTData(NBTTagCompound compound) {
		skills.clear(); // allows skills to reset on client without re-adding all the skills
		NBTTagList taglist = compound.getTagList("ZeldaSwordSkills");
		for (int i = 0; i < taglist.tagCount(); ++i) {
			NBTTagCompound skill = (NBTTagCompound) taglist.tagAt(i);
			byte id = skill.getByte("id");
			skills.put(id, SkillBase.getSkill(id).loadFromNBT(skill));
		}
		int[] stats = compound.getIntArray("zssStats");
		for (int i = 0; i < stats.length; ++i) {
			playerStats.put(Stats.values()[i], stats[i]);
		}
		receivedGear = compound.getByte("ZSSGearReceived");
		lastBootsID = compound.getInteger("lastBoots");
		lastHelmID = compound.getInteger("lastHelm");
		fairySpinOrbsReceived = compound.getInteger("fairySpinOrbsReceived");
		int maskID = compound.getInteger("borrowedMask");
		borrowedMask = maskID > -1 ? Item.itemsList[maskID] : null;
		maskStage = compound.getInteger("maskStage");
	}

	@Override
	public void init(Entity entity, World world) {}

}
