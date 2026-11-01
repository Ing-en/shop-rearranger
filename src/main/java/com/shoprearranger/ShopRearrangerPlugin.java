package com.shoprearranger;

import com.google.inject.Provides;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

/**
 * Lets the player rearrange the stock grid of the standard shop interface. It only moves the item widgets the
 * game already made; each keeps its own index and ops, so clicking a moved item does exactly what clicking it in
 * its original spot would. While the padlock is open, the mouse listener only watches presses on the grid to drag
 * items around, and the shop's item actions are cancelled the same way a blocked menu option is, so nothing about
 * the shop reaches the server until it's locked again.
 */
@Slf4j
@PluginDescriptor(
	name = "Shop Rearranger",
	description = "Rearrange the items in NPC shops: open the padlock in the top-left, then drag items where you want them",
	tags = {"shop", "store", "rearrange", "reorder", "layout", "sort", "items", "drag"}
)
public class ShopRearrangerPlugin extends Plugin
{
	static final String CONFIG_GROUP = "shoprearranger";
	private static final String LAYOUT_KEY_PREFIX = "layout.";

	private static final int PADLOCK_X = 8;
	private static final int PADLOCK_Y = 5;
	private static final int PADLOCK_WIDTH = 16;
	private static final int PADLOCK_HEIGHT = 20;
	/** How see-through the padlock is while locked and not hovered, so it stays out of the way. */
	private static final int PADLOCK_IDLE_OPACITY = 110;

	private static final int DROP_TARGET_COLOUR = 0xffff00;
	/** The dragged item's own icon is dimmed while its copy follows the mouse. */
	private static final int DRAG_SOURCE_OPACITY = 150;
	/** Pixels the mouse must move with the button held before a press becomes a drag. */
	private static final int DRAG_THRESHOLD = 4;

	/** Used only until the shop has shown two rows, which gives the real spacing. */
	private static final int DEFAULT_SPACING_X = 47;
	private static final int DEFAULT_SPACING_Y = 47;

	private static final String MENU_NAME = "<col=ff9040>%s</col>";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private ShopRearrangerConfig config;

	private volatile boolean unlocked;
	private String shopKey;
	private Map<Integer, Integer> layout = new LinkedHashMap<>();
	private boolean dumped;

	/** Where the game put each stock widget (by child index), learned before we move them. */
	private final Map<Integer, Point> vanillaPositions = new LinkedHashMap<>();
	/** Where we last put each item widget, to notice when the game redraws the grid. */
	private final Map<Widget, Point> placed = new IdentityHashMap<>();
	/** Item id to slot, as shown right now. */
	private final Map<Integer, Integer> currentSlots = new LinkedHashMap<>();
	private Widget[] widgetsBySlot = new Widget[0];
	private Grid grid;

	private final Set<Widget> ownWidgets = Collections.newSetFromMap(new IdentityHashMap<>());
	private Widget padlock;
	private boolean padlockHovered;
	private Widget dropTarget;
	private Widget dragGhost;
	private Widget dimmedSource;

	/** Each slot's area on the canvas, rebuilt every client tick for the mouse listener (AWT thread). */
	private volatile List<Cell> cells = List.of();
	private volatile Rectangle gridBounds;

	/* Drag state, written on the AWT thread and read on the client thread */
	private volatile int pressedItemId = -1;
	private volatile Point pressPoint;
	/** Mouse position relative to the pressed item's top-left, so the copy doesn't jump. */
	private volatile Point grabOffset;
	private volatile Point dragPoint;
	private volatile boolean dragging;

	private static final class Cell
	{
		final int slot;
		/** Item in the slot, or -1 when it's empty. */
		final int itemId;
		/** The whole cell including the gap around the item, so a drop between icons still lands. */
		final Rectangle area;
		/** The item icon itself. */
		final Rectangle icon;

		Cell(int slot, int itemId, Rectangle area, Rectangle icon)
		{
			this.slot = slot;
			this.itemId = itemId;
			this.area = area;
			this.icon = icon;
		}
	}

	private final MouseAdapter mouseListener = new MouseAdapter()
	{
		@Override
		public MouseEvent mousePressed(MouseEvent e)
		{
			if (!unlocked || e.getButton() != MouseEvent.BUTTON1)
			{
				return e;
			}
			Cell cell = cellAt(e.getPoint());
			if (cell == null || cell.itemId <= 0)
			{
				return e;
			}
			pressedItemId = cell.itemId;
			pressPoint = e.getPoint();
			grabOffset = new Point(e.getX() - cell.icon.x, e.getY() - cell.icon.y);
			dragPoint = e.getPoint();
			dragging = false;
			return e;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent e)
		{
			if (pressedItemId == -1)
			{
				return e;
			}
			Point start = pressPoint;
			if (!dragging && start != null && start.distance(e.getPoint()) > DRAG_THRESHOLD)
			{
				dragging = true;
			}
			dragPoint = e.getPoint();
			return e;
		}

		@Override
		public MouseEvent mouseReleased(MouseEvent e)
		{
			if (pressedItemId == -1)
			{
				return e;
			}
			if (dragging)
			{
				int itemId = pressedItemId;
				Cell target = cellAt(e.getPoint());
				int slot = target == null ? -1 : target.slot;
				clientThread.invokeLater(() -> drop(itemId, slot));
			}
			clearDrag();
			return e;
		}
	};

	private Cell cellAt(Point p)
	{
		Rectangle bounds = gridBounds;
		if (bounds == null || !bounds.contains(p))
		{
			return null;
		}
		for (Cell cell : cells)
		{
			if (cell.area.contains(p))
			{
				return cell;
			}
		}
		return null;
	}

	private void clearDrag()
	{
		pressedItemId = -1;
		pressPoint = null;
		grabOffset = null;
		dragPoint = null;
		dragging = false;
	}

	@Provides
	ShopRearrangerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ShopRearrangerConfig.class);
	}

	@Override
	protected void startUp()
	{
		spriteManager.addSpriteOverrides(PadlockSprite.values());
		mouseManager.registerMouseListener(mouseListener);
	}

	@Override
	protected void shutDown()
	{
		mouseManager.unregisterMouseListener(mouseListener);
		spriteManager.removeSpriteOverrides(PadlockSprite.values());
		clientThread.invokeLater(() ->
		{
			restoreVanilla();
			ownWidgets.forEach(w -> w.setHidden(true));
			reset();
		});
	}

	private void reset()
	{
		unlocked = false;
		clearDrag();
		shopKey = null;
		layout = new LinkedHashMap<>();
		dumped = false;
		vanillaPositions.clear();
		placed.clear();
		currentSlots.clear();
		widgetsBySlot = new Widget[0];
		grid = null;
		ownWidgets.clear();
		padlock = null;
		padlockHovered = false;
		dropTarget = null;
		dragGhost = null;
		dimmedSource = null;
		cells = List.of();
		gridBounds = null;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.SHOPMAIN)
		{
			// A fresh shop always opens locked, so a forgotten unlock can't swallow a buy click
			reset();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.SHOPMAIN)
		{
			reset();
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		// Editing the layout: the press that starts a drag must not buy or value the item under it
		if (unlocked && e.getMenuEntry().getParam1() == InterfaceID.Shopmain.ITEMS)
		{
			e.consume();
		}
	}

	@Subscribe
	public void onClientTick(ClientTick e)
	{
		Widget items = client.getWidget(InterfaceID.Shopmain.ITEMS);
		if (items == null || items.isHidden())
		{
			cells = List.of();
			gridBounds = null;
			return;
		}

		if (shopKey == null)
		{
			shopKey = readShopKey();
			if (shopKey == null)
			{
				return;
			}
			layout = ShopLayout.decode(configManager.getConfiguration(CONFIG_GROUP, LAYOUT_KEY_PREFIX + shopKey));
		}
		if (!dumped)
		{
			dumped = true;
			dumpStructure(items);
		}

		dropDeadWidgets(items);
		ensurePadlock();
		applyLayout(items);
		updateCells(items);
		updateEditWidgets(items);
	}

	/** The shop's title, lower-cased with tags stripped, so each shop keeps its own layout. */
	private String readShopKey()
	{
		Widget frame = client.getWidget(InterfaceID.Shopmain.FRAME);
		if (frame == null || frame.getDynamicChildren() == null)
		{
			return null;
		}
		for (Widget child : frame.getDynamicChildren())
		{
			if (child.getType() != WidgetType.TEXT || child.getText() == null)
			{
				continue;
			}
			String title = Text.removeTags(child.getText()).trim();
			if (!title.isEmpty())
			{
				return title.toLowerCase().replaceAll("[^a-z0-9]+", "_");
			}
		}
		return null;
	}

	/** Logs the shop grid's children once per opening, for checking the layout against the real interface. */
	private void dumpStructure(Widget items)
	{
		if (!log.isDebugEnabled())
		{
			return;
		}
		Widget[] children = items.getDynamicChildren();
		log.debug("Shop '{}': grid {}x{} scroll {} children {}", shopKey, items.getWidth(), items.getHeight(),
			items.getScrollHeight(), children == null ? 0 : children.length);
		if (children == null)
		{
			return;
		}
		for (Widget c : children)
		{
			log.debug("  [{}] type {} item {} qty {} at {},{} size {}x{} hidden {}", c.getIndex(), c.getType(),
				c.getItemId(), c.getItemQuantity(), c.getOriginalX(), c.getOriginalY(), c.getOriginalWidth(),
				c.getOriginalHeight(), c.isHidden());
		}
	}

	/** The game rebuilds parts of the shop now and then, which deletes anything we added there. */
	private void dropDeadWidgets(Widget items)
	{
		if (padlock != null && !isAlive(padlock, client.getWidget(InterfaceID.Shopmain.FRAME)))
		{
			ownWidgets.remove(padlock);
			padlock = null;
		}
		if (dropTarget != null && !isAlive(dropTarget, items))
		{
			ownWidgets.remove(dropTarget);
			dropTarget = null;
		}
		if (dragGhost != null && !isAlive(dragGhost, items))
		{
			ownWidgets.remove(dragGhost);
			dragGhost = null;
		}
	}

	private static boolean isAlive(Widget w, Widget parent)
	{
		return parent != null && parent.getChild(w.getIndex()) == w;
	}

	private void ensurePadlock()
	{
		if (padlock != null)
		{
			updatePadlock();
			return;
		}
		Widget frame = client.getWidget(InterfaceID.Shopmain.FRAME);
		if (frame == null)
		{
			return;
		}

		padlock = frame.createChild(-1, WidgetType.GRAPHIC);
		ownWidgets.add(padlock);
		padlock.setOriginalX(PADLOCK_X);
		padlock.setOriginalY(PADLOCK_Y);
		padlock.setOriginalWidth(PADLOCK_WIDTH);
		padlock.setOriginalHeight(PADLOCK_HEIGHT);
		padlock.setName(String.format(MENU_NAME, "Shop layout"));
		padlock.setHasListener(true);
		padlock.setOnOpListener((JavaScriptCallback) ev -> onPadlockOp(ev.getOp()));
		padlock.setOnMouseOverListener((JavaScriptCallback) ev -> padlockHovered = true);
		padlock.setOnMouseLeaveListener((JavaScriptCallback) ev -> padlockHovered = false);
		updatePadlock();
		padlock.revalidate();
	}

	private void updatePadlock()
	{
		padlock.setSpriteId(unlocked ? PadlockSprite.UNLOCKED.getSpriteId() : PadlockSprite.LOCKED.getSpriteId());
		padlock.setOpacity(unlocked || padlockHovered ? 0 : PADLOCK_IDLE_OPACITY);
		padlock.setAction(0, unlocked ? "Lock layout" : "Unlock layout");
		padlock.setAction(1, layout.isEmpty() ? null : "Reset layout");
	}

	private void onPadlockOp(int op)
	{
		if (op == 1)
		{
			unlocked = !unlocked;
			clearDrag();
		}
		else if (op == 2)
		{
			layout = new LinkedHashMap<>();
			clearDrag();
			configManager.unsetConfiguration(CONFIG_GROUP, LAYOUT_KEY_PREFIX + shopKey);
		}
	}

	private void applyLayout(Widget items)
	{
		List<Widget> stock = stockWidgets(items);
		List<Point> vanillaOrder = new ArrayList<>();
		for (Widget w : stock)
		{
			Point last = placed.get(w);
			if (last == null || last.x != w.getOriginalX() || last.y != w.getOriginalY())
			{
				// Not where we left it, so the game has just placed it: that's its own slot
				vanillaPositions.put(w.getIndex(), new Point(w.getOriginalX(), w.getOriginalY()));
				placed.remove(w);
			}
			vanillaOrder.add(vanillaPositions.get(w.getIndex()));
		}

		currentSlots.clear();
		grid = stock.isEmpty() ? null : Grid.from(vanillaOrder, items.getWidth(), stock.get(0).getOriginalWidth());
		if (grid == null)
		{
			widgetsBySlot = new Widget[0];
			return;
		}

		// Every row that fits in the window counts, so items can go into the empty rows below the stock
		int stockRows = (stock.size() + grid.cols - 1) / grid.cols;
		int visibleRows = (items.getHeight() - grid.y0 - stock.get(0).getOriginalHeight()) / grid.dy + 1;
		int rows = Math.max(stockRows, visibleRows);
		int slotCount = rows * grid.cols;
		int[] ids = stock.stream().mapToInt(Widget::getItemId).toArray();
		int[] slots = ShopLayout.assign(ids, layout, slotCount);

		widgetsBySlot = new Widget[slotCount];
		for (int i = 0; i < stock.size(); i++)
		{
			Widget w = stock.get(i);
			Point target = grid.position(slots[i]);
			if (w.getOriginalX() != target.x || w.getOriginalY() != target.y)
			{
				w.setOriginalX(target.x);
				w.setOriginalY(target.y);
				w.revalidate();
			}
			placed.put(w, target);
			widgetsBySlot[slots[i]] = w;
			currentSlots.putIfAbsent(ids[i], slots[i]);
		}
	}

	/** The game's item widgets in stock order, leaving out our own. */
	private List<Widget> stockWidgets(Widget items)
	{
		List<Widget> stock = new ArrayList<>();
		Widget[] children = items.getDynamicChildren();
		if (children == null)
		{
			return stock;
		}
		for (Widget child : children)
		{
			if (child != null && !ownWidgets.contains(child) && !child.isHidden() && child.getItemId() > 0)
			{
				stock.add(child);
			}
		}
		stock.sort(Comparator.comparingInt(Widget::getIndex));
		return stock;
	}

	/** Publishes each slot's canvas area for the mouse listener. Empty while locked, so the mouse is never touched. */
	private void updateCells(Widget items)
	{
		Widget sample = firstItem();
		if (!unlocked || grid == null || sample == null)
		{
			cells = List.of();
			gridBounds = null;
			return;
		}

		net.runelite.api.Point origin = items.getCanvasLocation();
		int left = origin.getX() - items.getScrollX();
		int top = origin.getY() - items.getScrollY();
		int width = sample.getWidth();
		int height = sample.getHeight();

		List<Cell> next = new ArrayList<>(widgetsBySlot.length);
		for (int slot = 0; slot < widgetsBySlot.length; slot++)
		{
			Point p = grid.position(slot);
			Rectangle icon = new Rectangle(left + p.x, top + p.y, width, height);
			Rectangle area = new Rectangle(icon.x - (grid.dx - width) / 2, icon.y - (grid.dy - height) / 2,
				grid.dx, grid.dy);
			Widget w = widgetsBySlot[slot];
			next.add(new Cell(slot, w == null ? -1 : w.getItemId(), area, icon));
		}
		cells = next;
		gridBounds = items.getBounds();
	}

	/** The drop-target outline and the copy of the dragged item that follows the mouse. */
	private void updateEditWidgets(Widget items)
	{
		Widget source = dragging ? widgetFor(pressedItemId) : null;
		if (dimmedSource != null && dimmedSource != source)
		{
			dimmedSource.setOpacity(0);
			dimmedSource = null;
		}
		Point mouse = dragPoint;
		Point offset = grabOffset;
		if (source == null || mouse == null || offset == null || grid == null)
		{
			hide(dragGhost);
			hide(dropTarget);
			return;
		}

		source.setOpacity(DRAG_SOURCE_OPACITY);
		dimmedSource = source;

		Cell target = cellAt(mouse);
		if (target != null)
		{
			if (dropTarget == null)
			{
				dropTarget = createRectangle(items, DROP_TARGET_COLOUR, 0);
			}
			Point p = grid.position(target.slot);
			dropTarget.setHidden(false);
			moveAndSize(dropTarget, p.x, p.y, source.getOriginalWidth(), source.getOriginalHeight());
		}
		else
		{
			hide(dropTarget);
		}

		// Created after everything else in the grid, so it draws on top of the other items
		if (dragGhost == null)
		{
			dragGhost = items.createChild(-1, WidgetType.GRAPHIC);
			ownWidgets.add(dragGhost);
		}
		net.runelite.api.Point origin = items.getCanvasLocation();
		dragGhost.setHidden(false);
		dragGhost.setItemId(source.getItemId());
		dragGhost.setItemQuantity(source.getItemQuantity());
		dragGhost.setItemQuantityMode(source.getItemQuantityMode());
		moveAndSize(dragGhost,
			mouse.x - offset.x - origin.getX() + items.getScrollX(),
			mouse.y - offset.y - origin.getY() + items.getScrollY(),
			source.getOriginalWidth(), source.getOriginalHeight());
	}

	private Widget createRectangle(Widget items, int colour, int opacity)
	{
		Widget w = items.createChild(-1, WidgetType.RECTANGLE);
		ownWidgets.add(w);
		w.setFilled(false);
		w.setTextColor(colour);
		w.setOpacity(opacity);
		return w;
	}

	private static void hide(Widget w)
	{
		if (w != null)
		{
			w.setHidden(true);
		}
	}

	private static void moveAndSize(Widget w, int x, int y, int width, int height)
	{
		if (w.getOriginalX() == x && w.getOriginalY() == y
			&& w.getOriginalWidth() == width && w.getOriginalHeight() == height)
		{
			return;
		}
		w.setOriginalX(x);
		w.setOriginalY(y);
		w.setOriginalWidth(width);
		w.setOriginalHeight(height);
		w.revalidate();
	}

	private Widget firstItem()
	{
		for (Widget w : widgetsBySlot)
		{
			if (w != null)
			{
				return w;
			}
		}
		return null;
	}

	private Widget widgetFor(int itemId)
	{
		Integer slot = currentSlots.get(itemId);
		return slot == null || slot >= widgetsBySlot.length ? null : widgetsBySlot[slot];
	}

	/**
	 * Moves the item to the slot. An empty slot just takes it; on an occupied one the setting decides whether the
	 * two items swap or the dragged item is inserted and the rest shift along.
	 */
	private void drop(int itemId, int slot)
	{
		Integer from = currentSlots.get(itemId);
		if (from == null || slot < 0 || slot >= widgetsBySlot.length || from == slot)
		{
			return;
		}
		Widget occupant = widgetsBySlot[slot];
		if (occupant != null && config.dropMode() == ShopRearrangerConfig.DropMode.INSERT)
		{
			int[] bySlot = new int[widgetsBySlot.length];
			for (int i = 0; i < bySlot.length; i++)
			{
				bySlot[i] = widgetsBySlot[i] == null ? -1 : widgetsBySlot[i].getItemId();
			}
			saveLayout(ShopLayout.insert(bySlot, from, slot));
			return;
		}

		Map<Integer, Integer> next = new LinkedHashMap<>(currentSlots);
		next.put(itemId, slot);
		if (occupant != null)
		{
			next.put(occupant.getItemId(), from);
		}
		saveLayout(next);
	}

	/** Saves every item's slot, not just the moved ones, so later stock changes don't shuffle the rest. */
	private void saveLayout(Map<Integer, Integer> next)
	{
		layout = next;
		configManager.setConfiguration(CONFIG_GROUP, LAYOUT_KEY_PREFIX + shopKey, ShopLayout.encode(next));
	}

	private void restoreVanilla()
	{
		if (dimmedSource != null)
		{
			dimmedSource.setOpacity(0);
		}
		placed.forEach((w, p) ->
		{
			Point vanilla = vanillaPositions.get(w.getIndex());
			if (vanilla != null)
			{
				w.setOriginalX(vanilla.x);
				w.setOriginalY(vanilla.y);
				w.revalidate();
			}
		});
	}

	/** The shop grid's geometry, read from where the game places its own items. */
	static final class Grid
	{
		final int x0;
		final int y0;
		final int dx;
		final int dy;
		final int cols;

		private Grid(int x0, int y0, int dx, int dy, int cols)
		{
			this.x0 = x0;
			this.y0 = y0;
			this.dx = dx;
			this.dy = dy;
			this.cols = cols;
		}

		Point position(int slot)
		{
			return new Point(x0 + (slot % cols) * dx, y0 + (slot / cols) * dy);
		}

		/**
		 * @param vanilla where the game placed each stock item, in stock order
		 */
		static Grid from(List<Point> vanilla, int containerWidth, int itemWidth)
		{
			if (vanilla.isEmpty() || vanilla.get(0) == null)
			{
				return null;
			}
			Point first = vanilla.get(0);
			Point second = vanilla.size() > 1 ? vanilla.get(1) : null;
			int dx = second != null && second.y == first.y && second.x > first.x ? second.x - first.x : DEFAULT_SPACING_X;

			// The first item on a lower row gives the column count and the row spacing
			for (int i = 1; i < vanilla.size() && vanilla.get(i) != null; i++)
			{
				Point p = vanilla.get(i);
				if (p.y != first.y)
				{
					return new Grid(first.x, first.y, dx, p.y - first.y, i);
				}
			}

			// Only one row so far: fit as many columns as the container allows
			int cols = Math.max(1, (containerWidth - first.x - itemWidth) / dx + 1);
			return new Grid(first.x, first.y, dx, DEFAULT_SPACING_Y, cols);
		}
	}
}
