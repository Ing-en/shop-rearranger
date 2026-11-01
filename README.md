# Shop Rearranger

Rearrange the items in NPC shops, the way you'd lay out a bank tab.

## How to use

1. Open any shop. A padlock sits in the top-left corner of the window.
2. Click the padlock to unlock the layout.
3. Drag an item onto another item to swap them, or onto any empty space in the grid (including the empty rows below the stock) to move it there.
4. Click the padlock again to lock. Left-click goes back to **Value** as normal.

Each shop remembers its own layout. Right-click the padlock and choose **Reset layout** to go back to the
shop's normal order. Every shop opens locked, so you can't forget the editor is on.

## What it does and doesn't do

- It only moves the shop's own item icons inside the stock grid. Items keep their size, and clicking a
  moved item buys, sells or values exactly that item.
- Your inventory on the right-hand side is never touched.
- While unlocked, the shop's item actions (Value, Buy) are paused so a drag can never buy anything. Lock the
  padlock to shop normally again. Moving items is handled entirely by RuneLite and sends nothing to the game.
