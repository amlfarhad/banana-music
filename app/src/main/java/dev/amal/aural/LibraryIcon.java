package dev.amal.aural;
import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Consistent rose line icons for the four library destinations. */
final class LibraryIcon extends Drawable {
    private final int kind,size;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    LibraryIcon(int kind,int size){this.kind=kind;this.size=size;}
    @Override public void draw(Canvas c){c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/28f,getBounds().height()/28f);paint.setColor(0xffff4b78);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);Path p=new Path();
        if(kind==0){p.moveTo(3,5);p.lineTo(18,5);p.moveTo(3,10);p.lineTo(15,10);p.moveTo(3,15);p.lineTo(12,15);p.moveTo(20,12);p.lineTo(20,22);p.moveTo(20,12);p.lineTo(25,10);p.lineTo(25,19);c.drawOval(15,20,20,24,paint);c.drawOval(20,17,25,21,paint);}
        else if(kind==1){c.drawRoundRect(3,6,17,24,2,2,paint);p.moveTo(6,3);p.lineTo(19,3);p.quadTo(21,3,21,5);p.lineTo(21,9);c.drawCircle(18,16,7,paint);c.drawCircle(18,16,1.5f,paint);}
        else if(kind==2){c.drawArc(4,4,24,24,40,285,false,paint);p.moveTo(21,3);p.lineTo(21,9);p.lineTo(15,9);p.moveTo(14,8);p.lineTo(14,14);p.lineTo(18,16);}
        else{p.moveTo(12,6);p.lineTo(12,21);p.moveTo(12,6);p.lineTo(24,3);p.lineTo(24,18);p.moveTo(12,10);p.lineTo(24,7);c.drawOval(5,19,12,24,paint);c.drawOval(17,16,24,21,paint);}
        c.drawPath(p,paint);c.restore();}
    @Override public int getIntrinsicWidth(){return size;}@Override public int getIntrinsicHeight(){return size;}@Override public void setAlpha(int a){paint.setAlpha(a);invalidateSelf();}@Override public void setColorFilter(ColorFilter f){paint.setColorFilter(f);invalidateSelf();}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
