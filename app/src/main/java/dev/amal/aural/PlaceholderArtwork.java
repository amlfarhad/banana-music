package dev.amal.aural;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/** Static vector cover for playlists whose web layout provides no artwork URL. */
final class PlaceholderArtwork extends Drawable {
    private final GradientDrawable background;
    private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    PlaceholderArtwork(String title){
        int[][] palette={{0xffb9a2b6,0xff54475e},{0xffadc4bc,0xff3b625e},
            {0xffbec4d3,0xff465575},{0xffd1b6a4,0xff80605b},{0xffc7b7c1,0xff734359}};
        background=new GradientDrawable(GradientDrawable.Orientation.TL_BR,palette[Math.floorMod(title.hashCode(),palette.length)]);
        ink.setColor(0xd9ffffff);
    }
    @Override public void draw(Canvas canvas){
        background.setBounds(getBounds());background.draw(canvas);
        canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/320f,getBounds().height()/320f);
        Path beam=new Path();beam.moveTo(142,104);beam.lineTo(226,86);beam.lineTo(226,112);beam.lineTo(142,130);beam.close();canvas.drawPath(beam,ink);
        canvas.drawRoundRect(new RectF(142,104,154,213),5,5,ink);canvas.drawRoundRect(new RectF(214,86,226,195),5,5,ink);
        canvas.drawOval(new RectF(105,195,154,226),ink);canvas.drawOval(new RectF(177,177,226,208),ink);canvas.restore();
    }
    @Override public int getIntrinsicWidth(){return 320;}
    @Override public int getIntrinsicHeight(){return 320;}
    @Override public void setAlpha(int alpha){ink.setAlpha(alpha);background.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){ink.setColorFilter(filter);background.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.OPAQUE;}
}
