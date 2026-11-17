package com.shadex.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Four-column grid of square cells; modules span whole cells like the iOS Control Center. */
final class CCGrid extends ViewGroup {
    static final class LP extends ViewGroup.LayoutParams {
        final int col, row, colSpan, rowSpan;

        LP(int col, int row, int colSpan, int rowSpan) {
            super(0, 0);
            this.col = col;
            this.row = row;
            this.colSpan = colSpan;
            this.rowSpan = rowSpan;
        }
    }

    private final int gap;
    private int cell, offsetX, maxHeight, minCell;

    CCGrid(Context c, int gap) {
        super(c);
        this.gap = gap;
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
    }

    /**
     * Fit the whole grid into this height by making the cells smaller (centred), so the page
     * never needs scrolling. Cells don't shrink below minCell.
     */
    void fitHeight(int maxHeight, int minCell) {
        this.maxHeight = maxHeight;
        this.minCell = minCell;
        requestLayout();
    }

    void put(View v, int col, int row, int colSpan, int rowSpan) {
        addView(v, new LP(col, row, colSpan, rowSpan));
    }

    private int span(int n) {
        return n * cell + (n - 1) * gap;
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        cell = (w - 3 * gap) / 4;
        int rows = 0;
        for (int i = 0; i < getChildCount(); i++) {
            LP lp = (LP) getChildAt(i).getLayoutParams();
            rows = Math.max(rows, lp.row + lp.rowSpan);
        }
        if (maxHeight > 0 && rows > 0) {
            int byHeight = (maxHeight - (rows - 1) * gap) / rows;
            cell = Math.max(Math.min(cell, byHeight), Math.min(cell, minCell));
        }
        offsetX = (w - (4 * cell + 3 * gap)) / 2;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            LP lp = (LP) v.getLayoutParams();
            if (v instanceof Tiles.Module) ((Tiles.Module) v).onCell(cell);
            v.measure(MeasureSpec.makeMeasureSpec(span(lp.colSpan), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(span(lp.rowSpan), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, rows == 0 ? 0 : span(rows));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            LP lp = (LP) v.getLayoutParams();
            int x = offsetX + lp.col * (cell + gap), y = lp.row * (cell + gap);
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
        }
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LP;
    }
}
