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

import java.util.EnumSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import zeldaswordskills.client.gui.GuiBuffBar;
import zeldaswordskills.entity.ZSSPlayerInfo;
import zeldaswordskills.lib.Config;
import zeldaswordskills.network.ActivateSkillPacket;
import zeldaswordskills.network.GetBombPacket;
import zeldaswordskills.skills.ICombo;
import zeldaswordskills.skills.ILockOnTarget;
import zeldaswordskills.skills.SkillActive;
import zeldaswordskills.skills.SkillBase;
import zeldaswordskills.skills.sword.ArmorBreak;
import zeldaswordskills.skills.sword.Dodge;
import zeldaswordskills.skills.sword.Parry;
import zeldaswordskills.skills.sword.SpinAttack;
import zeldaswordskills.skills.sword.SwordBasic;
import cpw.mods.fml.client.registry.KeyBindingRegistry;
import cpw.mods.fml.client.registry.KeyBindingRegistry.KeyHandler;
import cpw.mods.fml.common.TickType;
import cpw.mods.fml.common.network.PacketDispatcher;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class ZSSKeyHandler extends KeyHandler
{
	/** Key index for easy handling and retrieval of keys and key descriptions */
	public static final byte KEY_SKILL_ACTIVATE = 0, KEY_NEXT_TARGET = 1, KEY_ATTACK = 2,
			KEY_LEFT = 3, KEY_RIGHT = 4, KEY_DOWN = 5, KEY_BLOCK = 6, KEY_BOMB = 7,
			KEY_TOGGLE_AUTOTARGET = 8, KEY_TOGGLE_BUFFBAR = 9;

	/** Key descriptions - this is what the player sees when changing key bindings in-game */
	public static final String[] desc = { "key.zss.activate.desc","key.zss.next.desc",
		"key.zss.attack.desc","key.zss.left.desc","key.zss.right.desc","key.zss.down.desc",
		"key.zss.block.desc","key.zss.bomb.desc","key.zss.toggleat.desc","key.zss.togglebuff.desc"};

	/** Default key values */
	private static final int[] keyValues = {Keyboard.KEY_X, Keyboard.KEY_TAB, Keyboard.KEY_UP,
		Keyboard.KEY_LEFT, Keyboard.KEY_RIGHT, Keyboard.KEY_DOWN, Keyboard.KEY_RCONTROL,
		Keyboard.KEY_B, Keyboard.KEY_PERIOD, Keyboard.KEY_V};

	public static final KeyBinding[] keys = new KeyBinding[desc.length];

	/**
	 * Initializes keybindings and registers a new KeyHandler instance
	 */
	public static final void init() {
		boolean[] repeat = new boolean[desc.length];
		for (int i = 0; i < desc.length; ++i) {
			keys[i] = new KeyBinding(desc[i], keyValues[i]);
			repeat[i] = false;
		}
		KeyBindingRegistry.registerKeyBinding(new ZSSKeyHandler(keys, repeat));
	}

	public ZSSKeyHandler(KeyBinding[] keys, boolean[] repeat) { super(keys, repeat); }

	@Override
	public String getLabel() { return StatCollector.translateToLocal("key.zss.label"); }

	@Override
	public EnumSet<TickType> ticks() { return EnumSet.of(TickType.CLIENT); }

	@Override
	public void keyDown(EnumSet<TickType> types, KeyBinding kb, boolean tickEnd, boolean isRepeat) {
		if (tickEnd) {
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.currentScreen == null && ZSSPlayerInfo.get(mc.thePlayer) != null) {
				if (kb == keys[KEY_SKILL_ACTIVATE]) {
					SkillBase skill = ZSSPlayerInfo.get(mc.thePlayer).getPlayerSkill(SkillBase.swordBasic);
					PacketDispatcher.sendPacketToServer(new ActivateSkillPacket(skill).makePacket());
				} else if (kb == keys[KEY_BOMB]) {
					PacketDispatcher.sendPacketToServer(new GetBombPacket().makePacket());
				} else if (kb == keys[KEY_TOGGLE_AUTOTARGET]) {
					mc.thePlayer.addChatMessage(StatCollector.translateToLocalFormatted("zsskey.chat.toggleat",(Config.toggleAutoTarget() ? StatCollector.translateToLocal("zsskey.chat.enable") : StatCollector.translateToLocal("zsskey.chat.disable"))));
				} else if (kb == keys[KEY_TOGGLE_BUFFBAR]) {
					GuiBuffBar.shouldDisplay = !GuiBuffBar.shouldDisplay;
				} else {
					handleTargetingKeys(mc, kb);
				}
			}
		}
	}

	/**
	 * All ILockOnTarget skill related keys are handled here
	 */
	private void handleTargetingKeys(Minecraft mc, KeyBinding kb)
	{
		ZSSPlayerInfo skills = ZSSPlayerInfo.get(mc.thePlayer);
		ILockOnTarget skill = skills.getTargetingSkill();
		boolean canInteract = skills.canInteract();

		if (!(skill instanceof SkillActive) || !((SkillActive) skill).isActive()) { return; }

		if (kb == keys[KEY_NEXT_TARGET]) {
			skill.getNextTarget(mc.thePlayer);
		} else if (kb == keys[KEY_ATTACK]) {
			if (!canInteract) {
				if (skills.isSkillActive(SkillBase.spinAttack)) {
					((SpinAttack) skills.getPlayerSkill(SkillBase.spinAttack)).keyPressed(kb, mc.thePlayer);
				}
				return;
			} else {
				keys[KEY_ATTACK].pressed = true;
			}
			// Handle special cases for Art of the Sword; e.g. activating leaping blow
			if (skill instanceof SwordBasic) {
				if (skills.isSkillActive(SkillBase.leapingBlow)) {
					// do nothing, waiting for leaping blow to finish upon landing
				} else if (skills.hasSkill(SkillBase.dash) && mc.thePlayer.isBlocking() && mc.thePlayer.onGround) {
					PacketDispatcher.sendPacketToServer(new ActivateSkillPacket(SkillBase.dash).makePacket());
				} else if (skills.canUseSkill(SkillBase.swordBeam) && mc.thePlayer.isSneaking()) {
					PacketDispatcher.sendPacketToServer(new ActivateSkillPacket(SkillBase.swordBeam).makePacket());
				} else {
					mc.thePlayer.swingItem();
					if (((ICombo) skill).onAttack(mc.thePlayer)) {
						mc.playerController.attackEntity(mc.thePlayer, skill.getCurrentTarget());
					}
				}
				// handle separately so can attack and begin charging without pressing key twice
				if (skills.hasSkill(SkillBase.armorBreak)) {
					((ArmorBreak) skills.getPlayerSkill(SkillBase.armorBreak)).keyPressed(mc.thePlayer);
				}
			} else { // Generic ILockOnTarget skill simply attacks; handles possibility of being ICombo
				mc.thePlayer.swingItem();
				if (skill instanceof ICombo && ((ICombo) skill).onAttack(mc.thePlayer)) {
					mc.playerController.attackEntity(mc.thePlayer, skill.getCurrentTarget());
				}
			}
		} else if (kb == keys[KEY_LEFT] || kb == keys[KEY_RIGHT]) {
			if (kb == keys[KEY_RIGHT]) {
				keys[KEY_RIGHT].pressed = true;
			} else {
				keys[KEY_LEFT].pressed = true;
			}
			
			if (skill instanceof SwordBasic && canInteract) {
				if (skills.hasSkill(SkillBase.dodge)) {
					((Dodge) skills.getPlayerSkill(SkillBase.dodge)).keyPressed(kb, mc.thePlayer);
				}
				if (skills.hasSkill(SkillBase.spinAttack)) {
					((SpinAttack) skills.getPlayerSkill(SkillBase.spinAttack)).keyPressed(kb, mc.thePlayer);
				}
			}
		}  else if (kb == keys[KEY_DOWN] && canInteract) {
			if (skill instanceof SwordBasic && skills.hasSkill(SkillBase.parry)) {
				((Parry) skills.getPlayerSkill(SkillBase.parry)).keyPressed(mc.thePlayer);
			}
		} else if (kb == keys[KEY_BLOCK] && canInteract) {
			//if (!skills.isSkillActive(SkillBase.leapingBlow)) {
			keys[KEY_BLOCK].pressed = true; // handled each tick in ZSSPlayerInfo' onUpdate
			//}
		}
	}

	@Override
	public void keyUp(EnumSet<TickType> types, KeyBinding kb, boolean tickEnd) {
		if (tickEnd) {
			if (kb == keys[KEY_BLOCK]) {
				keys[KEY_BLOCK].pressed = false;
			} else if (kb == keys[KEY_ATTACK]) {
				keys[KEY_ATTACK].pressed = true;
			} else if (kb == keys[KEY_LEFT]) {
				keys[KEY_LEFT].pressed = false;
			} else if (kb == keys[KEY_RIGHT]) {
				keys[KEY_RIGHT].pressed = false;
			}
		}
	}
}
