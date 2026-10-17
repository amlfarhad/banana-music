package dev.amal.aural;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.widget.Button;

/** Draw the vector at the true geometric center, independent of text/font padding. */
final class PlaybackButton extends Button {
    private final Drawable icon;
    PlaybackButton(Context context,Drawable icon){super(context);this.icon=icon;icon.setCallback(this);setText("");setPadding(0,0,0,0);setMinWidth(0);setMinimumWidth(0);setMinHeight(0);setMinimumHeight(0);}
    @Override protected void onDraw(Canvas canvas){int w=icon.getIntrinsicWidth(),h=icon.getIntrinsicHeight();int x=(getWidth()-w)/2,y=(getHeight()-h)/2;icon.setBounds(x,y,x+w,y+h);icon.draw(canvas);}
    @Override protected boolean verifyDrawable(Drawable drawable){return drawable==icon||super.verifyDrawable(drawable);}
}
