package dev.amal.aural;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Small vector transport icons; no bitmap decoding or animated effects. */
final class PlaybackIcon extends Drawable {
    private final int kind;
    private final int size;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int mode=-1;
    PlaybackIcon(int kind,int size){this.kind=kind;this.size=size;setBounds(0,0,size,size);setMode(0);}
    void setMode(int value){if(mode==value)return;mode=value;invalidateSelf();}
    @Override public void draw(Canvas canvas){
        canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);
        paint.setColor(kind>=2?0xfff5f4f8:mode==0?0xffaaa9b4:0xffff4b78);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        Path path=new Path();
        if(kind==0){path.moveTo(3,6);path.lineTo(6,6);path.cubicTo(10,6,14,18,18,18);path.lineTo(21,18);path.moveTo(18,15);path.lineTo(21,18);path.lineTo(18,21);path.moveTo(3,18);path.lineTo(6,18);path.cubicTo(10,18,14,6,18,6);path.lineTo(21,6);path.moveTo(18,3);path.lineTo(21,6);path.lineTo(18,9);}
        else if(kind==1){path.moveTo(4,10);path.lineTo(4,8);path.quadTo(4,5,7,5);path.lineTo(20,5);path.moveTo(17,2);path.lineTo(20,5);path.lineTo(17,8);path.moveTo(20,14);path.lineTo(20,16);path.quadTo(20,19,17,19);path.lineTo(4,19);path.moveTo(7,16);path.lineTo(4,19);path.lineTo(7,22);}
        else if(kind==2){path.moveTo(15,5);path.lineTo(8,12);path.lineTo(15,19);}
        else if(kind==4){path.moveTo(9,5);path.lineTo(16,12);path.lineTo(9,19);}
        else if(kind==3){paint.setStyle(Paint.Style.FILL);if(mode==1){canvas.drawRoundRect(6,5,10,19,1,1,paint);canvas.drawRoundRect(14,5,18,19,1,1,paint);}else{path.moveTo(7,4);path.lineTo(20,12);path.lineTo(7,20);path.close();}}
        canvas.drawPath(path,paint);
        if(kind==1&&mode==1){paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(10);paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);canvas.drawText("1",12,15.5f,paint);}
        canvas.restore();
    }
    @Override public int getIntrinsicWidth(){return size;}
    @Override public int getIntrinsicHeight(){return size;}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
