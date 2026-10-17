package dev.amal.aural;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import android.view.View;
import android.view.ViewTreeObserver;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.WeakHashMap;

/** Bounded artwork decoding; no network or image work on the UI thread. */
final class ArtworkLoader {
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WeakHashMap<ImageView, Pending> pending = new WeakHashMap<>();
    private volatile boolean closed;
    private java.io.File disk;
    void init(android.content.Context context){disk=new java.io.File(context.getCacheDir(),"artwork-v2");disk.mkdirs();}
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    void load(String raw, ImageView view, int width) {
        Pending prior=pending.remove(view);if(prior!=null)prior.cancel();
        if(closed)return;
        if (raw == null || raw.isEmpty()) return;
        String sized = raw.replace("{w}", String.valueOf(width)).replace("{h}", String.valueOf(width));
        // Preserve the supplied aspect ratio; request bounded pixels from Apple's image CDN.
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("/(\\d+)x(\\d+)([^/]*\\.(?:jpg|png|webp))").matcher(sized);
        if (!sized.contains("signature=") && match.find()) {
            int w = Integer.parseInt(match.group(1)), h = Integer.parseInt(match.group(2));
            if (w > 0) sized = match.replaceFirst("/" + width + "x" + Math.max(1, width * h / w) + match.group(3));
        }
        final String url = sized;
        view.setTag(url);
        Bitmap hit = cache.get(url);
        if (hit != null) { view.setImageBitmap(hit); return; }
        Pending work=new Pending(view,url);pending.put(view,work);work.watch(()->pool.execute(() -> {
            HttpURLConnection connection = null;
            try {
                if(work.cancelled)return;
                URL address = new URL(url);
                String host = address.getHost();
                if (!address.getProtocol().equals("https") || !(host.equals("music.apple.com") || host.endsWith(".mzstatic.com") || host.endsWith(".blobstore.apple.com"))) return;
                java.io.File file=new java.io.File(disk,digest(url));
                byte[] bytes=null;
                if(file.isFile()&&file.length()<=3*1024*1024){bytes=java.nio.file.Files.readAllBytes(file.toPath());file.setLastModified(System.currentTimeMillis());}
                if(bytes==null){
                    connection = (HttpURLConnection) address.openConnection();
                    connection.setConnectTimeout(7000);connection.setReadTimeout(7000);connection.setInstanceFollowRedirects(false);
                    if(connection.getResponseCode()!=200)return;
                    ByteArrayOutputStream data=new ByteArrayOutputStream();
                    try(InputStream stream=connection.getInputStream()){
                        byte[] buffer=new byte[8192];int n;
                        while((n=stream.read(buffer))!=-1){if(work.cancelled)return;if(data.size()+n>3*1024*1024)return;data.write(buffer,0,n);}
                    }
                    bytes=data.toByteArray();
                    java.io.File temporary=new java.io.File(disk,file.getName()+"."+Thread.currentThread().getId()+".tmp");
                    java.nio.file.Files.write(temporary.toPath(),bytes);temporary.renameTo(file);trimDisk();
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0) return;
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > width * 2 || options.outHeight / options.inSampleSize > width * 2) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (image != null) {
                    cache.put(url, image);
                    ui.post(() -> { if (!work.cancelled&&url.equals(view.getTag())) view.setImageBitmap(image);work.cancel();if(pending.get(view)==work)pending.remove(view); });
                }
            } catch (Exception ignored) {
                // Keep the native artwork placeholder on a network failure.
            } finally { if (connection != null) connection.disconnect();ui.post(()->{work.cancel();if(pending.get(view)==work)pending.remove(view);}); }
        }));
    }
    private final class Pending implements View.OnAttachStateChangeListener,ViewTreeObserver.OnScrollChangedListener {
        final ImageView view;final String url;volatile boolean cancelled;boolean submitted;Runnable start;ViewTreeObserver observer;
        Pending(ImageView view,String url){this.view=view;this.url=url;}
        void watch(Runnable runnable){start=runnable;view.addOnAttachStateChangeListener(this);observe();view.post(this::check);}
        void observe(){observer=view.getViewTreeObserver();if(observer.isAlive())observer.addOnScrollChangedListener(this);}
        void check(){if(cancelled||submitted||closed)return;Rect bounds=new Rect();if(view.isAttachedToWindow()&&view.getGlobalVisibleRect(bounds)&&bounds.width()>8&&bounds.height()>8){submitted=true;if(observer!=null&&observer.isAlive())observer.removeOnScrollChangedListener(this);start.run();}}
        public void onScrollChanged(){check();}
        public void onViewAttachedToWindow(View v){if(observer==null||!observer.isAlive())observe();check();}
        public void onViewDetachedFromWindow(View v){cancel();if(pending.get(view)==this)pending.remove(view);}
        void cancel(){cancelled=true;if(observer!=null&&observer.isAlive())observer.removeOnScrollChangedListener(this);view.removeOnAttachStateChangeListener(this);}
    }
    private static String digest(String value)throws Exception{byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();}
    private synchronized void trimDisk(){java.io.File[] files=disk.listFiles((d,n)->!n.endsWith(".tmp"));if(files==null)return;java.util.Arrays.sort(files,java.util.Comparator.comparingLong(java.io.File::lastModified));long total=0;for(java.io.File f:files)total+=f.length();for(java.io.File f:files){if(total<=24*1024*1024)break;total-=f.length();f.delete();}}
    void trim() { cache.trimToSize(2 * 1024 * 1024); }
    void close() { closed=true;for(Pending work:pending.values())work.cancel();pending.clear();pool.shutdownNow();cache.evictAll(); }
}
