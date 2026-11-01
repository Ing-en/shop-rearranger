package com.shoprearranger;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches RuneLite with this plugin loaded for local testing. Run with {@code gradlew run}.
 */
public class ShopRearrangerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ShopRearrangerPlugin.class);
		RuneLite.main(args);
	}
}
