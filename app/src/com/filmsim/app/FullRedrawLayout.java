package com.filmsim.app;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.ViewParent;
import android.widget.RelativeLayout;

/**
 * Root layout that turns every partial redraw into a full one.
 *
 * On the A7 II the overlay screen keeps stale pixels across partial redraws: a panel that was hidden
 * vanished on a full redraw, then reappeared as soon as something small (the exposure bar) updated.
 * Redrawing the whole layout every time means an old frame can never show through.
 */
public class FullRedrawLayout extends RelativeLayout {
    public FullRedrawLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public ViewParent invalidateChildInParent(int[] location, Rect dirty) {
        invalidate();
        return null;
    }
}
