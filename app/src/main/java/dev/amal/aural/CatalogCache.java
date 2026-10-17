package dev.amal.aural;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Account-local metadata cache. Credentials and cookies never enter this cache. */
final class CatalogCache {
    private final File directory;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<String,JSONObject> models=new HashMap<>();
    private volatile boolean closed;
    CatalogCache(Context context,Consumer<JSONObject> restored){
        directory=new File(context.getFilesDir(),"catalog-v2");directory.mkdirs();
        worker.execute(()->{File[] files=directory.listFiles((d,n)->n.endsWith(".json"));if(files==null)return;
            for(File file:files){if(closed)break;try{
                if(file.length()>8*1024*1024)continue;
                JSONObject model=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
                String key=model.optString("key");if(key.isEmpty())continue;
                ui.post(()->{if(!closed&&!models.containsKey(key)){models.put(key,model);restored.accept(model);}});
            }catch(Exception ignored){}}
        });
    }
    JSONObject get(String key){return models.get(key);}
    java.util.Collection<JSONObject> all(){return new java.util.ArrayList<>(models.values());}
    void put(JSONObject model){String key=model.optString("key");if(key.isEmpty()||closed)return;models.put(key,model);
        worker.execute(()->{try{File file=new File(directory,digest(key)+".json"),temp=new File(directory,file.getName()+".tmp");
            Files.write(temp.toPath(),model.toString().getBytes(StandardCharsets.UTF_8));temp.renameTo(file);trim();
        }catch(Exception ignored){}});
    }
    void decode(String encoded,Consumer<JSONObject> result){if(closed)return;worker.execute(()->{
        JSONObject value=null;try{Object outer=new org.json.JSONTokener(encoded).nextValue();value=outer instanceof String?new JSONObject((String)outer):outer instanceof JSONObject?(JSONObject)outer:null;}catch(Exception ignored){}
        final JSONObject model=value;ui.post(()->{if(!closed)result.accept(model);});
    });}
    void clear(){models.clear();worker.execute(()->{File[] fs=directory.listFiles();if(fs!=null)for(File f:fs)f.delete();});}
    void close(){closed=true;worker.shutdown();models.clear();}
    private void trim(){File[] fs=directory.listFiles((d,n)->n.endsWith(".json"));if(fs==null)return;
        Arrays.sort(fs,Comparator.comparingLong(File::lastModified));long bytes=0;for(File f:fs)bytes+=f.length();
        for(File f:fs){if(bytes<=32*1024*1024)break;bytes-=f.length();f.delete();}
    }
    private static String digest(String key)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();}
}
