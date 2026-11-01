package com.shoprearranger;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ShopRearrangerPlugin.CONFIG_GROUP)
public interface ShopRearrangerConfig extends Config
{
	enum DropMode
	{
		SWAP("Swap"),
		INSERT("Insert");

		private final String name;

		DropMode(String name)
		{
			this.name = name;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	@ConfigItem(
		keyName = "dropMode",
		name = "Drop mode",
		description = "Swap: the dragged item and the item it's dropped on trade places.<br>"
			+ "Insert: the dragged item goes in that spot and the items in between shift along by one, like the bank's Insert mode."
	)
	default DropMode dropMode()
	{
		return DropMode.SWAP;
	}
}
