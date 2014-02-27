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

package zeldaswordskills.skills;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

public class SkillPassive extends SkillBase
{
	protected SkillPassive(String name, byte id) {
		super(name, id, true);
	}

	protected SkillPassive(SkillBase skill) { super(skill); }
	
	@Override
	public SkillPassive newInstance() { return new SkillPassive(this); }
	
	@Override
	public List<String> getDescription(EntityPlayer player) { return tooltip; }
	
	@Override
	protected void levelUp(EntityPlayer player) {}
	
	@Override
	public final void writeToNBT(NBTTagCompound compound) {
		compound.setByte("id", id);
		compound.setByte("level", level);
	}
	
	@Override
	public final void readFromNBT(NBTTagCompound compound) {
		level = compound.getByte("level");
	}
	
	@Override
	public final SkillPassive loadFromNBT(NBTTagCompound compound) {
		SkillPassive skill = (SkillPassive) getSkillList()[compound.getByte("id")].newInstance();
		skill.readFromNBT(compound);
		return skill;
	}
}
