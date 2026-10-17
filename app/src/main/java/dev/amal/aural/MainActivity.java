package dev.amal.aural;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaDrm;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Native TV UI; Apple's own origin remains responsible for subscription playback and sign-in. */
public final class MainActivity extends Activity {
    private static final int BG = 0xff111114, PANEL = 0xff1b1b20, TEXT = 0xfff7f7fa, MUTED = 0xffa0a0ab, ACCENT = 0xfffa2d55;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Button> navigation = new ArrayList<>();
    private final ArtworkLoader artworks = new ArtworkLoader();
    private SharedPreferences preferences;
    private WebView engine, popupWeb;
    private FrameLayout stage, popupStage;
    private LinearLayout rail, nativeContent, page, nowBar;
    private ScrollView scroller;
    private ProgressBar loading;
    private TextView status, trackTitle, trackArtist, authHint, largeTrackTitle, largeTrackArtist;
    private Button selected, playButton,shuffleButton,repeatButton;
    private PlaybackIcon shuffleIcon,repeatIcon,playIcon;
    private int shuffleMode,repeatMode;
    private SeekBar seekBar;
    private ImageView trackArtwork, largeTrackArtwork;
    private View cursor, popupCursor;
    private Dialog popup;
    private String adapter = "", storefront = "us", lastUrl = "", lastModel = "", lastError = "", lastArtwork = "", renderedUrl="", waitingForPage="";
    private JSONObject model, playback = new JSONObject();
    private boolean webMode, pointer, selecting, longSelect, seeking, playing, pollInFlight, snapshotInFlight, alive, nowScreen, searchScreen, libraryScreen;
    private int catalogRevision = -1;
    private String lastMetadataKey="", lastSessionState="";
    private CatalogCache catalog;
    private String activeDataKey="library-hub";
    private final List<String> dataHistory=new ArrayList<>();
    private View dataBody;
    private final java.util.Map<String,Integer> trackSelections=new java.util.HashMap<>(),shelfSelections=new java.util.HashMap<>(),shelfRows=new java.util.HashMap<>();
    private boolean dataInFlight;
    private String pendingPlayback="";
    private long loadStarted, lastSnapshot, popupAllowedUntil, refreshUntil;
    private float pointerX, pointerY;
    private MediaSession mediaSession;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences = getSharedPreferences("aural", MODE_PRIVATE);
        artworks.init(this);
        storefront = preferences.getString("storefront", "us");
        try (InputStream in = getAssets().open("tv-adapter.js")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[8192]; int n;
            while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            adapter = out.toString("UTF-8");
        } catch (Exception e) { throw new IllegalStateException("Missing TV adapter", e); }
        try(InputStream in=getAssets().open("music-data.js")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
            adapter+=";\n"+out.toString("UTF-8");
        }catch(Exception e){throw new IllegalStateException("Missing music data client",e);}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        createUI();
        catalog=new CatalogCache(this,fresh->{if(fresh.optString("key").equals(activeDataKey)&&!nowScreen&&!searchScreen)displayData(fresh);});
        selected = navigation.get(4);
        createAudioSession();
        showLibrary();selected.requestFocus();
        // Draw the native TV screen before provider startup can block the UI.
        handler.postDelayed(this::initializeEngine,250);
        alive = true; handler.post(poll);
    }

    private void initializeEngine() {
        if(engine!=null||!alive||isFinishing()||isDestroyed())return;
        engine = new WebView(this);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        configureWeb(engine);
        // Attached and measurable, but covered by native content. No browser surface during browsing.
        stage.addView(engine, 0, new FrameLayout.LayoutParams(-1, -1));
        engine.setAlpha(0.01f); engine.setFocusable(false);
        engine.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        cursor = createCursor(stage);
        engine.loadUrl("https://music.apple.com/" + storefront + "/library/all-playlists");
        loadStarted = SystemClock.elapsedRealtime();
        if(catalog.get(activeDataKey)==null){loading.setVisibility(View.VISIBLE);status.setText("Connecting to Apple Music…");}
    }

    private void createUI() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff20171e,0xff111216,0xff171a22}));
        root.setPadding(dp(20), dp(18), dp(18), dp(16)); setContentView(root);
        rail = new LinearLayout(this); rail.setOrientation(LinearLayout.VERTICAL); rail.setPadding(dp(10), dp(16), dp(10), dp(10));rail.setBackground(glass(false,22));
        LinearLayout.LayoutParams railParams=new LinearLayout.LayoutParams(dp(162),-1);railParams.rightMargin=dp(18);root.addView(rail,railParams);
        TextView brand = label("Banana Music", 22, TEXT); brand.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        brand.setPadding(dp(4),0,0,dp(6));rail.addView(brand); TextView sub = label("APPLE MUSIC ON TV", 8, MUTED); sub.setPadding(dp(4),0,0,0);sub.setLetterSpacing(.12f);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, dp(24)); rail.addView(sub, subParams);
        String[] names = {"Search", "Home", "New", "Radio", "Library"};
        for (int i = 0; i < names.length; i++) {
            final int index = i; Button b = navButton(names[i]); navigation.add(b); rail.addView(b, navParams());
            b.setOnClickListener(v -> {
                selected = b; nowScreen = false;searchScreen=false;libraryScreen=false;waitingForPage="";hideWeb();
                dataHistory.clear();
                if (index == 0) showSearch();
                else if (index == 4) {
                    showLibrary();
                } else loadData(index==1?"home":index==2?"new":"radio",names[index],false);
                updateRail();
            });
        }
        rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        Button now = navButton("Now playing"); now.setOnClickListener(v -> { hideWeb(); nowScreen = true; renderNowPlaying(); }); rail.addView(now, navParams());
        Button account = navButton("Apple account"); account.setOnClickListener(v -> signIn()); rail.addView(account, navParams());
        Button options = navButton("Settings"); options.setOnClickListener(v -> settings()); rail.addView(options, navParams());
        TextView small = label("PERSONAL • UNOFFICIAL", 8, 0xff6d6d79); small.setPadding(dp(12), dp(12), 0, 0); rail.addView(small);

        LinearLayout right = new LinearLayout(this); right.setOrientation(LinearLayout.VERTICAL);
        root.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        stage = new FrameLayout(this); right.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        nativeContent = new LinearLayout(this); nativeContent.setOrientation(LinearLayout.VERTICAL);
        nativeContent.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff19151b,0xff111216,0xff171a22}));
        stage.addView(nativeContent, new FrameLayout.LayoutParams(-1, -1));
        status = label("", 10, MUTED); status.setPadding(dp(4), dp(2), 0, dp(6)); nativeContent.addView(status);
        loading = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); loading.setIndeterminate(true);
        loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        nativeContent.addView(loading, new LinearLayout.LayoutParams(-1, dp(2)));
        scroller = new ScrollView(this); scroller.setFillViewport(false); scroller.setClipToPadding(false); scroller.setVerticalScrollBarEnabled(false);
        nativeContent.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(6), dp(10), dp(8), dp(24)); scroller.addView(page);
        authHint = label("", 14, TEXT); authHint.setPadding(dp(12), dp(12), dp(12), dp(12)); authHint.setBackgroundColor(PANEL);
        FrameLayout.LayoutParams hint = new FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP); stage.addView(authHint, hint); authHint.setVisibility(View.GONE);
        nowBar = new LinearLayout(this); nowBar.setGravity(Gravity.CENTER_VERTICAL); nowBar.setPadding(dp(14), dp(10), dp(14), dp(10)); nowBar.setBackground(glass(false,24));
        LinearLayout.LayoutParams nowParams = new LinearLayout.LayoutParams(-1, dp(70)); nowParams.topMargin = dp(12); right.addView(nowBar, nowParams);
        trackArtwork = new ImageView(this); trackArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP); trackArtwork.setBackground(round(0xff33333c, 6, 0)); trackArtwork.setClipToOutline(true);
        nowBar.addView(trackArtwork, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout metadata = new LinearLayout(this); metadata.setOrientation(LinearLayout.VERTICAL); metadata.setPadding(dp(12),0,dp(10),0);
        nowBar.addView(metadata, new LinearLayout.LayoutParams(0,-2,1));
        trackTitle = label("Your music. Your subscription.", 13, TEXT); trackTitle.setMaxLines(1); trackTitle.setEllipsize(TextUtils.TruncateAt.END); metadata.addView(trackTitle);
        trackArtist = label("Sign in with Apple to get started", 10, MUTED); trackArtist.setMaxLines(1); metadata.addView(trackArtist);
        shuffleMode=preferences.getBoolean("shuffle",false)?1:0;repeatMode=preferences.getInt("repeat",0);
        shuffleIcon=new PlaybackIcon(0,dp(22));shuffleIcon.setMode(shuffleMode);shuffleButton=transport(shuffleIcon,"Shuffle");shuffleButton.setOnClickListener(v->{shuffleMode=shuffleMode==0?1:0;preferences.edit().putBoolean("shuffle",shuffleMode==1).apply();shuffleIcon.setMode(shuffleMode);dataCall("setShuffle("+shuffleMode+")",null);toast(shuffleMode==1?"Shuffle on":"Shuffle off");});nowBar.addView(shuffleButton,new LinearLayout.LayoutParams(dp(46),dp(46)));
        Button previous = transport(new PlaybackIcon(2,dp(22)), "Previous song"); previous.setOnClickListener(v -> media("previous")); nowBar.addView(previous, new LinearLayout.LayoutParams(dp(46),dp(46)));
        playIcon=new PlaybackIcon(3,dp(22));playButton = transport(playIcon, "Play or pause"); playButton.setOnClickListener(v -> media("toggle")); nowBar.addView(playButton,new LinearLayout.LayoutParams(dp(56),dp(46)));
        Button next = transport(new PlaybackIcon(4,dp(22)), "Next song"); next.setOnClickListener(v -> media("next")); nowBar.addView(next,new LinearLayout.LayoutParams(dp(46),dp(46)));
        repeatIcon=new PlaybackIcon(1,dp(22));repeatIcon.setMode(repeatMode);repeatButton=transport(repeatIcon,"Repeat");repeatButton.setOnClickListener(v->{repeatMode=repeatMode==0?2:repeatMode==2?1:0;preferences.edit().putInt("repeat",repeatMode).apply();repeatIcon.setMode(repeatMode);dataCall("setRepeat("+repeatMode+")",null);toast(repeatMode==0?"Repeat off":repeatMode==2?"Repeat all":"Repeat one");});nowBar.addView(repeatButton,new LinearLayout.LayoutParams(dp(46),dp(46)));
    }

    private void configureWeb(WebView view) {
        WebSettings s = view.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); s.setSupportMultipleWindows(true); s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true); s.setBuiltInZoomControls(false); s.setDisplayZoomControls(false);
        // Apple's initialization can await image resources; keep its resource policy intact.
        s.setBlockNetworkImage(false);
        // Retain the device's real WebView version; request the desktop site without claiming a different browser.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace(" Mobile ", " ") + " AuralTV/0.1");
        view.setBackgroundColor(PANEL);
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(view,true);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return !trusted(request.getUrl());
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (v == engine && isMusic(url)) {
                    v.evaluateJavascript(adapter, ignored -> {
                        call("quality(" + preferences.getBoolean("highQuality",true) + ")", null);
                        dataCall("setCountry("+JSONObject.quote(storefront)+")",null);
                        for(JSONObject savedModel:catalog.all())dataCall("restore("+savedModel.toString()+")",null);
                        dataCall("setModes("+(preferences.getBoolean("shuffle",false)?1:0)+","+preferences.getInt("repeat",0)+")",null);
                        dataCall("prefetch()",null);
                        if(!activeDataKey.equals("library-hub")&&!activeDataKey.equals("search-input"))requestData(activeDataKey);
                        lastSnapshot = 0;
                    });
                }
                CookieManager.getInstance().flush();
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && v == engine) {
                    loading.setVisibility(View.GONE); status.setText("Connection interrupted");
                    if (model == null) empty("Apple Music is unavailable", "Check the stick’s internet connection, then choose Reload in Settings.");
                }
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler ssl, SslError error) {
                ssl.cancel(); toast("Secure connection failed. Check the TV’s date and network.");
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    Uri origin=request.getOrigin();
                    if ("https".equals(origin.getScheme()) && "music.apple.com".equals(origin.getHost()) && (origin.getPort()==-1||origin.getPort()==443)) {
                        for (String resource: request.getResources()) if (resource.equals(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                            request.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID}); return;
                        }
                    }
                    request.deny();
                });
            }
            @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message result) {
                if (!isMusic(v.getUrl()) || (!gesture && SystemClock.elapsedRealtime() > popupAllowedUntil)) return false;
                openPopup(result); return true;
            }
            @Override public void onCloseWindow(WebView window) { if (popup != null && window == popupWeb) popup.dismiss(); }
        });
    }

    private boolean trusted(Uri uri) {
        String host = uri.getHost();
        return "https".equals(uri.getScheme()) && host != null && (host.equals("apple.com") || host.endsWith(".apple.com"));
    }
    private boolean isMusic(String url) { return url != null && "https".equals(Uri.parse(url).getScheme()) && "music.apple.com".equals(Uri.parse(url).getHost()); }

    private void openPopup(Message message) {
        if (popup != null) popup.dismiss();
        popup = new Dialog(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) { return remote(event) || super.dispatchKeyEvent(event); }
        };
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setBackgroundColor(BG);
        TextView instruction = label("Apple sign-in  ·  D-pad moves the pointer; OK selects; Back returns",14,TEXT);
        instruction.setPadding(dp(18),dp(14),dp(18),dp(14)); layout.addView(instruction);
        popupStage = new FrameLayout(this); layout.addView(popupStage,new LinearLayout.LayoutParams(-1,0,1));
        popupWeb = new WebView(this); configureWeb(popupWeb);popupWeb.getSettings().setBlockNetworkImage(false);popupStage.addView(popupWeb,new FrameLayout.LayoutParams(-1,-1));
        popupCursor = createCursor(popupStage);
        popup.setContentView(layout); popup.setOnDismissListener(d -> {
            if (popupWeb != null) { popupWeb.destroy(); popupWeb = null; }
            popupStage = null; popupCursor = null; popup = null; CookieManager.getInstance().flush();
            lastSnapshot = 0; if (webMode) resetPointer();
        });
        popup.show(); popup.getWindow().setLayout(-1,-1);
        popup.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        pointer = true; resetPointer();
        ((WebView.WebViewTransport)message.obj).setWebView(popupWeb); message.sendToTarget();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            if (!pollInFlight && engine != null && isMusic(engine.getUrl())) {
                pollInFlight = true;
                engine.evaluateJavascript("window.AuralTV ? JSON.stringify(AuralTV.state()) : null", result -> {
                    pollInFlight = false;
                    JSONObject state = object(result);
                    if (state != null) {
                        playback = state; updatePlayback();
                        if(state.optBoolean("ready")&&!pendingPlayback.isEmpty()){String queued=pendingPlayback;pendingPlayback="";dataCall(queued,null);status.setText("Your Apple Music");}
                        if(libraryScreen&&state.optBoolean("ready")){loading.setVisibility(View.GONE);status.setText(state.optBoolean("needsSignIn")?"Sign in with Apple to access your library":"Choose playlists, albums or songs");}
                        String url = state.optString("url");
                        if (!url.equals(lastUrl)) { lastUrl = url; lastModel = ""; lastSnapshot = 0; }
                        boolean changed = state.optInt("revision",0) != catalogRevision;
                        catalogRevision = state.optInt("revision",0);
                        pollData();
                    }
                });
            }
            if (!webMode && loading.getVisibility() == View.VISIBLE && SystemClock.elapsedRealtime()-loadStarted > 25000) {
                loading.setVisibility(View.GONE);
                status.setText("Apple Music is taking longer than usual. Reload in Settings if needed.");
                if (model == null) empty("Waiting for Apple Music", "The TV interface is ready. Apple’s catalog has not loaded yet.");
            }
            handler.postDelayed(this, 250);
        }
    };

    private void dataCall(String expression,Consumer<String> callback){
        if(engine==null||!isMusic(engine.getUrl())){if(callback!=null)callback.accept("null");return;}
        engine.evaluateJavascript("window.BananaData ? BananaData."+expression+" : null",r->{if(callback!=null)callback.accept(r);});
    }
    private void playData(String expression){if(!playback.optBoolean("ready")){pendingPlayback=expression;status.setText("Connecting audio…");}else dataCall(expression,null);}
    private void requestData(String key){dataCall("request("+JSONObject.quote(key)+")",null);}
    private void loadData(String key,String title,boolean push){
        if(push&&!activeDataKey.isEmpty()&&!activeDataKey.equals(key))dataHistory.add(activeDataKey);
        activeDataKey=key;nowScreen=false;searchScreen=false;libraryScreen=false;hideWeb();loadStarted=SystemClock.elapsedRealtime();
        JSONObject cached=catalog.get(key);
        if(cached!=null){displayData(cached);dataCall("restore("+cached.toString()+")",null);}
        else{restorePage();page.removeAllViews();page.addView(label(title,30,TEXT));loading.setVisibility(View.VISIBLE);status.setText("Loading "+title+"…");}
        requestData(key);
    }
    private void displayData(JSONObject fresh){model=fresh;render(fresh);loading.setVisibility(View.GONE);status.setText("Your Apple Music");android.util.Log.i("BananaUI","content "+fresh.optString("key")+" openMs="+(SystemClock.elapsedRealtime()-loadStarted));}
    private void pollData(){
        if(dataInFlight||catalog==null||engine==null)return;dataInFlight=true;
            engine.evaluateJavascript("window.BananaData ? JSON.stringify({events:BananaData.drain()}) : null",encoded->{
                catalog.decode(encoded,payload->{dataInFlight=false;if(payload==null)return;
                    JSONArray events=payload.optJSONArray("events");if(events==null)return;
                    for(int i=0;i<events.length();i++){JSONObject event=events.optJSONObject(i);if(event==null)continue;
                        String key=event.optString("key");JSONObject fresh=event.optJSONObject("model");
                        if(fresh!=null){JSONObject old=catalog.get(key);catalog.put(fresh);
                            if(key.equals(activeDataKey)&&!nowScreen&&!searchScreen&&!webMode){
                                if(old==null||model==null||!key.equals(model.optString("key")))displayData(fresh);else loading.setVisibility(View.GONE);
                            }
                        }else if(key.equals(activeDataKey)&&catalog.get(key)==null){loading.setVisibility(View.GONE);status.setText(event.optString("error","Apple Music is unavailable."));}
                    }
                });
            });
    }
    private void restorePage(){if(dataBody!=null){nativeContent.removeView(dataBody);dataBody=null;}if(scroller.getParent()==null)nativeContent.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));}
    private void replaceBody(View view){if(dataBody!=null)nativeContent.removeView(dataBody);nativeContent.removeView(scroller);dataBody=view;nativeContent.addView(view,new LinearLayout.LayoutParams(-1,0,1));}
    private void renderShelves(JSONObject data){
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        TextView title=label(data.optString("title"),30,TEXT);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));title.setPadding(dp(6),dp(8),0,dp(14));body.addView(title);
        android.widget.ListView list=new android.widget.ListView(this);list.setDivider(null);list.setDividerHeight(dp(18));list.setItemsCanFocus(true);list.setCacheColorHint(android.graphics.Color.TRANSPARENT);
        body.addView(list,new LinearLayout.LayoutParams(-1,0,1));JSONArray rows=data.optJSONArray("rows");final JSONArray shelves=rows==null?new JSONArray():rows;
        list.setAdapter(new android.widget.BaseAdapter(){
            public int getCount(){return shelves.length();}public Object getItem(int p){return shelves.optJSONObject(p);}public long getItemId(int p){return p;}
            public View getView(int position,View recycled,android.view.ViewGroup parent){
                if(recycled instanceof LinearLayout&&Integer.valueOf(position).equals(recycled.getTag()))return recycled;
                JSONObject row=shelves.optJSONObject(position);JSONArray values=row.optJSONArray("items");
                LinearLayout rowView=new LinearLayout(MainActivity.this);rowView.setTag(position);rowView.setOrientation(LinearLayout.VERTICAL);rowView.setPadding(dp(6),0,dp(6),dp(8));
                TextView heading=label(row.optString("title"),17,TEXT);heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));heading.setPadding(0,0,0,dp(10));rowView.addView(heading);
                NativeShelf gallery=new NativeShelf(values,data.optString("key")+"/"+position);gallery.setId(View.generateViewId());
                rowView.addView(gallery,new LinearLayout.LayoutParams(-1,dp(226)));
                gallery.setOnKeyListener((v,key,event)->{
                    if(key==KeyEvent.KEYCODE_DPAD_LEFT&&gallery.position==0){if(event.getAction()==KeyEvent.ACTION_DOWN)selected.requestFocus();return true;}
                    if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){
                        if(event.getAction()!=KeyEvent.ACTION_DOWN)return true;int next=position+(key==KeyEvent.KEYCODE_DPAD_UP?-1:1);
                        if(next<0){selected.requestFocus();return true;}if(next>=shelves.length()){playButton.requestFocus();return true;}
                        shelfRows.put(data.optString("key"),next);list.setSelection(next);list.post(()->{View target=list.getChildAt(next-list.getFirstVisiblePosition());if(target instanceof LinearLayout){View g=((LinearLayout)target).getChildAt(1);g.requestFocus();}});return true;
                    }return false;
                });
                if(position==0){selected.setNextFocusRightId(gallery.getId());}
                return rowView;
            }
        });replaceBody(body);
        int remembered=shelfRows.getOrDefault(data.optString("key"),0);list.setSelection(remembered);list.post(()->{View target=list.getChildAt(remembered-list.getFirstVisiblePosition());if(target instanceof LinearLayout)((LinearLayout)target).getChildAt(1).requestFocus();});
    }
    /** A bounded, left-aligned TV shelf. Only visible cards are inflated. */
    private final class NativeShelf extends FrameLayout {
        final JSONArray values;final String stateKey;int position,first;final java.util.Map<Integer,View> cards=new java.util.HashMap<>();
        NativeShelf(JSONArray values,String key){super(MainActivity.this);this.stateKey=key;this.values=values==null?new JSONArray():values;position=Math.min(Math.max(0,this.values.length()-1),shelfSelections.getOrDefault(key,0));setFocusable(true);setFocusableInTouchMode(true);setClipChildren(true);setOnFocusChangeListener((v,focused)->{highlight();if(focused)selected.setNextFocusRightId(getId());});}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);post(this::drawCards);}
        private void drawCards(){
            int visible=Math.max(1,getWidth()/dp(170));if(position<first)first=position;if(position>=first+visible)first=position-visible+1;
            int end=Math.min(values.length(),first+visible+1);
            for(Integer index:new java.util.ArrayList<>(cards.keySet()))if(index<first||index>=end){removeView(cards.remove(index));}
            for(int index=first;index<end;index++){
                View card=cards.get(index);if(card==null){card=cardTile(values.optJSONObject(index),false);card.setFocusable(false);card.setFocusableInTouchMode(false);final int target=index;card.setOnClickListener(v->{position=target;highlight();activate(values.optJSONObject(target));});cards.put(index,card);addView(card);}
                FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(154),dp(218));lp.leftMargin=(index-first)*dp(170);card.setLayoutParams(lp);
            }highlight();JSONArray keys=new JSONArray();for(int i=first;i<end;i++){String key=values.optJSONObject(i).optString("key");if(!key.isEmpty())keys.put(key);}dataCall("warm("+keys.toString()+")",null);
        }
        private void highlight(){shelfSelections.put(stateKey,position);for(java.util.Map.Entry<Integer,View> card:cards.entrySet())card.getValue().setBackground(glass(hasFocus()&&card.getKey()==position,14));setContentDescription(values.length()==0?"No items":values.optJSONObject(position).optString("title"));}
        @Override public boolean onKeyDown(int key,KeyEvent event){
            if(key==KeyEvent.KEYCODE_DPAD_RIGHT||key==KeyEvent.KEYCODE_DPAD_LEFT){int next=position+(key==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1);if(next>=0&&next<values.length()){position=next;drawCards();}return true;}
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){if(event.getRepeatCount()==0&&values.length()>0)activate(values.optJSONObject(position));return true;}return super.onKeyDown(key,event);
        }
        @Override public boolean onKeyUp(int key,KeyEvent event){if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)return true;return super.onKeyUp(key,event);}
    }
    private void renderTracks(JSONObject data){
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        JSONArray array=data.optJSONArray("tracks");final JSONArray tracks=array==null?new JSONArray():array;
        JSONObject detail=data.optJSONObject("detail");
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(6),dp(10),dp(10),dp(12));body.addView(header);
        ImageView cover=artView();cover.setImageDrawable(new PlaceholderArtwork(data.optString("title")));header.addView(cover,new LinearLayout.LayoutParams(dp(72),dp(72)));if(detail!=null)artworks.load(detail.optString("artwork"),cover,180);
        LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);words.setPadding(dp(18),0,dp(12),0);header.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        TextView title=label(data.optString("title"),23,TEXT);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));words.addView(title);words.addView(label(tracks.length()+" songs",12,MUTED));
        Button shuffle=control("Shuffle","Shuffle playlist");shuffle.setTextSize(14);shuffle.setOnClickListener(v->playData("play("+JSONObject.quote(data.optString("key"))+",0,true)"));header.addView(shuffle,new LinearLayout.LayoutParams(dp(94),dp(40)));
        android.widget.ListView list=new android.widget.ListView(this);list.setId(View.generateViewId());list.setFocusableInTouchMode(true);list.setDivider(null);list.setDividerHeight(dp(3));list.setSelector(glass(true,12));list.setCacheColorHint(android.graphics.Color.TRANSPARENT);list.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setAdapter(new android.widget.BaseAdapter(){public int getCount(){return tracks.length();}public Object getItem(int p){return tracks.optJSONObject(p);}public long getItemId(int p){return p;}
            public View getView(int p,View recycled,android.view.ViewGroup parent){
                LinearLayout row;
                if(recycled instanceof LinearLayout)row=(LinearLayout)recycled;else{
                    row=new LinearLayout(MainActivity.this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(14),dp(6),dp(16),dp(6));row.setMinimumHeight(dp(52));row.setBackground(round(0x08ffffff,12,0));
                    row.addView(label("",12,MUTED),new LinearLayout.LayoutParams(dp(42),-2));LinearLayout texts=new LinearLayout(MainActivity.this);texts.setOrientation(LinearLayout.VERTICAL);row.addView(texts,new LinearLayout.LayoutParams(0,-2,1));TextView song=label("",15,TEXT);song.setSingleLine();song.setEllipsize(TextUtils.TruncateAt.END);texts.addView(song);TextView artist=label("",11,MUTED);artist.setSingleLine();artist.setEllipsize(TextUtils.TruncateAt.END);texts.addView(artist);row.addView(label("",12,MUTED));
                }
                JSONObject item=tracks.optJSONObject(p);((TextView)row.getChildAt(0)).setText(String.valueOf(p+1));LinearLayout texts=(LinearLayout)row.getChildAt(1);((TextView)texts.getChildAt(0)).setText(item.optString("title"));((TextView)texts.getChildAt(1)).setText(item.optString("subtitle"));((TextView)row.getChildAt(2)).setText(item.optString("duration"));row.setContentDescription((p+1)+", "+item.optString("title"));return row;
            }});
        list.setOnItemClickListener((p,v,index,id)->{list.setItemChecked(index,true);playData("play("+JSONObject.quote(data.optString("key"))+","+index+",null)");});
        list.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_DPAD_LEFT){if(event.getAction()==KeyEvent.ACTION_DOWN)selected.requestFocus();return true;}return false;});
        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int index,long id){trackSelections.put(data.optString("key"),index);}});
        selected.setNextFocusRightId(list.getId());shuffle.setNextFocusDownId(list.getId());replaceBody(body);int remembered=Math.min(Math.max(0,tracks.length()-1),trackSelections.getOrDefault(data.optString("key"),0));list.post(()->{list.setSelection(remembered);list.requestFocus();});
    }

    private void requestSnapshot() {
        if (snapshotInFlight) return;
        snapshotInFlight = true;
        call("JSON.stringify(AuralTV.snapshot())", result -> {
            snapshotInFlight = false; lastSnapshot = SystemClock.elapsedRealtime(); JSONObject fresh = object(result);
            if (fresh == null) return;
            if(!waitingForPage.isEmpty()&&!Uri.parse(fresh.optString("url")).getPath().replaceAll("/+$", "").endsWith(waitingForPage.replaceAll("/+$", "")))return;
            waitingForPage="";
            JSONArray rows = fresh.optJSONArray("rows");
            String signature = fresh.toString();
            if (rows != null && (rows.length() > 0 || fresh.optJSONObject("detail") != null)) {
                if (!signature.equals(lastModel)) { lastModel = signature; model = fresh; render(fresh); }
                loading.setVisibility(View.GONE); status.setText(playback.optBoolean("needsSignIn") ? "Browse Apple Music · Sign in for your subscription" : "Your Apple Music");
                if (fresh.optString("url").matches("https://music\\.apple\\.com/[a-z]{2}/new.*")) preferences.edit().putString("newCache", signature).apply();
            } else if (!fresh.optString("title").isEmpty() && SystemClock.elapsedRealtime()-loadStarted > 4500) {
                loading.setVisibility(View.GONE);
                empty(playback.optBoolean("needsSignIn") ? "Make it your music" : fresh.optString("title"), playback.optBoolean("needsSignIn") ? "Sign in with Apple to see your library and recommendations." : "This Apple page has no supported native shelves. Use Apple Player in Settings if needed.");
            }
        });
    }

    private void navigate(String url, String title) {
        if(engine==null){toast("Apple Music is starting. Try again in a moment.");return;}
        nowScreen = false;searchScreen=false;libraryScreen=false;waitingForPage=Uri.parse(url).getPath();lastSnapshot = 0; loading.setVisibility(View.VISIBLE); loadStarted = SystemClock.elapsedRealtime();refreshUntil=loadStarted+8000;
        status.setText("Opening " + title + "…");
        if (isMusic(engine.getUrl())) call("navigate(" + JSONObject.quote(url) + ")", value -> { if (scalar(value).isEmpty()) engine.loadUrl(url); });
        else engine.loadUrl(url);
    }

    private void render(JSONObject data) {
        if(data.optBoolean("api")){if(data.optJSONArray("tracks")!=null)renderTracks(data);else renderShelves(data);return;}
        restorePage();
        View prior=getCurrentFocus();boolean hadPageFocus=under(page,prior);Object priorTag=prior==null?null:prior.getTag();
        boolean samePage=data.optString("url").equals(renderedUrl);int priorScroll=scroller.getScrollY();renderedUrl=data.optString("url");
        View preferred=null,restore=null;
        page.removeAllViews(); scroller.scrollTo(0,0); seekBar = null;largeTrackTitle=null;largeTrackArtist=null;largeTrackArtwork=null;
        String title = data.optString("title","Explore"); if (title.isEmpty()) title="Explore";
        TextView heading = label(title,30,TEXT); heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0)); page.addView(heading);
        JSONObject detail = data.optJSONObject("detail");
        if (detail != null) {
            LinearLayout summary = new LinearLayout(this); summary.setGravity(Gravity.CENTER_VERTICAL); summary.setPadding(0,dp(14),0,dp(14)); page.addView(summary);
            ImageView cover = artView();cover.setImageDrawable(new PlaceholderArtwork(detail.optString("title"))); summary.addView(cover,new LinearLayout.LayoutParams(dp(150),dp(150))); artworks.load(detail.optString("artwork"),cover,360);
            LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL); words.setPadding(dp(24),0,0,0); summary.addView(words,new LinearLayout.LayoutParams(0,-2,1));
            TextView description = label(detail.optString("subtitle"),16,MUTED); description.setMaxLines(3); words.addView(description);
            JSONArray controls = data.optJSONArray("controls");
            if (controls != null) for (int i=0;i<controls.length();i++) {
                JSONObject item=controls.optJSONObject(i); if(item==null)continue;
                Button b=control(item.optString("title"),item.optString("title")); b.setTag(item.optString("id"));b.setTextSize(14); b.setOnClickListener(v->activate(item));
                if(preferred==null)preferred=b;if(priorTag!=null&&priorTag.equals(b.getTag()))restore=b;
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(132),dp(44)); lp.topMargin=dp(12); words.addView(b,lp);
            }
        }
        if(detail==null){
            JSONArray controls=data.optJSONArray("controls");
            if(controls!=null&&controls.length()>0){
                LinearLayout buttons=new LinearLayout(this);page.addView(buttons);
                for(int i=0;i<controls.length();i++){
                    JSONObject item=controls.optJSONObject(i);if(item==null)continue;
                    Button b=control(item.optString("title"),item.optString("title"));b.setTextSize(14);b.setTag(item.optString("id"));b.setOnClickListener(v->activate(item));
                    buttons.addView(b,new LinearLayout.LayoutParams(dp(160),dp(48)));if(preferred==null)preferred=b;
                }
            }
        }
        List<List<View>> focusRows = new ArrayList<>();
        JSONArray rows=data.optJSONArray("rows"); if(rows==null)return;
        for (int ri=0;ri<rows.length();ri++) {
            JSONObject row=rows.optJSONObject(ri); if(row==null)continue; JSONArray items=row.optJSONArray("items"); if(items==null)continue;
            TextView rowTitle=label(row.optString("title"),17,TEXT); rowTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
            LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(-1,-2); titleParams.topMargin=dp(22); titleParams.bottomMargin=dp(12); page.addView(rowTitle,titleParams);
            List<View> focus = new ArrayList<>(); focusRows.add(focus);
            boolean tracks=row.optString("kind").equals("track");
            if (tracks && detail != null) {
                for(int i=0;i<items.length();i++) {
                    JSONObject item=items.optJSONObject(i); if(item==null)continue;
                    LinearLayout tile=trackTile(item,i); focus.add(tile);
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54)); lp.bottomMargin=dp(4); page.addView(tile,lp);
                }
            } else {
                HorizontalScrollView horizontal=new HorizontalScrollView(this); horizontal.setHorizontalScrollBarEnabled(false); horizontal.setClipToPadding(false); horizontal.setPadding(dp(5),dp(6),dp(5),dp(8));
                page.addView(horizontal,new LinearLayout.LayoutParams(-1,-2));
                LinearLayout strip=new LinearLayout(this); strip.setPadding(0,0,dp(20),0); horizontal.addView(strip);
                boolean hero=row.optString("kind").equals("hero");
                for(int i=0;i<items.length();i++) {
                    JSONObject item=items.optJSONObject(i); if(item==null)continue;
                    LinearLayout tile=cardTile(item,hero); focus.add(tile);
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(hero?300:154),-2); lp.rightMargin=dp(16); strip.addView(tile,lp);
                }
            }
        }
        for(int row=0;row<focusRows.size();row++) {
            List<View> list=focusRows.get(row);
            for(int i=0;i<list.size();i++) {
                View view=list.get(i); view.setNextFocusLeftId(i==0?selected.getId():list.get(i-1).getId());
                view.setNextFocusRightId(i+1<list.size()?list.get(i+1).getId():view.getId());
                if(row>0&&!focusRows.get(row-1).isEmpty())view.setNextFocusUpId(focusRows.get(row-1).get(Math.min(i,focusRows.get(row-1).size()-1)).getId());
                if(row+1<focusRows.size()&&!focusRows.get(row+1).isEmpty())view.setNextFocusDownId(focusRows.get(row+1).get(Math.min(i,focusRows.get(row+1).size()-1)).getId());
                else view.setNextFocusDownId(playButton.getId());
            }
        }
        for(Button nav:navigation) if(!focusRows.isEmpty()&&!focusRows.get(0).isEmpty())nav.setNextFocusRightId(focusRows.get(0).get(0).getId());
        if(preferred==null&&!focusRows.isEmpty()&&!focusRows.get(0).isEmpty())preferred=focusRows.get(0).get(0);
        for(List<View> row:focusRows)for(View tile:row)if(priorTag!=null&&priorTag.equals(tile.getTag()))restore=tile;
        final View target=restore!=null?restore:preferred;
        if(hadPageFocus&&target!=null)page.post(()->{if(samePage)scroller.scrollTo(0,priorScroll);target.requestFocus();});
        updateRail();
    }

    private LinearLayout cardTile(JSONObject item, boolean hero) {
        LinearLayout tile=new LinearLayout(this); tile.setTag(item.optString("id"));tile.setOrientation(LinearLayout.VERTICAL); tile.setPadding(dp(4),dp(4),dp(4),dp(8)); focusTile(tile); tile.setOnClickListener(v->activate(item));
        ImageView artwork=artView();artwork.setImageDrawable(new PlaceholderArtwork(item.optString("title"))); tile.addView(artwork,new LinearLayout.LayoutParams(-1,dp(hero?166:146))); artworks.load(item.optString("artwork"),artwork,hero?640:320);
        TextView name=label(item.optString("title"),hero?16:14,TEXT);name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0)); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); name.setPadding(dp(6),dp(10),dp(6),0); tile.addView(name,new LinearLayout.LayoutParams(-1,dp(hero?48:46)));
        TextView subtitle=label(item.optString("subtitle"),11,MUTED); subtitle.setMaxLines(1); subtitle.setEllipsize(TextUtils.TruncateAt.END); subtitle.setPadding(dp(4),0,dp(4),0); tile.addView(subtitle);
        tile.setContentDescription(item.optString("title")+", "+item.optString("subtitle")); return tile;
    }
    private LinearLayout trackTile(JSONObject item,int index) {
        LinearLayout tile=new LinearLayout(this);tile.setTag(item.optString("id")); tile.setGravity(Gravity.CENTER_VERTICAL); tile.setPadding(dp(12),0,dp(14),0); focusTile(tile); tile.setOnClickListener(v->activate(item));
        TextView number=label(String.valueOf(item.optInt("number",index+1)),12,MUTED); tile.addView(number,new LinearLayout.LayoutParams(dp(36),-2));
        LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);tile.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        TextView title=label(item.optString("title"),15,TEXT);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0)); title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END);words.addView(title);
        if(!item.optString("subtitle").isEmpty()){TextView artist=label(item.optString("subtitle"),11,MUTED);artist.setMaxLines(1);artist.setEllipsize(TextUtils.TruncateAt.END);words.addView(artist);}
        tile.addView(label(item.optString("duration"),12,MUTED)); return tile;
    }
    private void focusTile(View tile) {
        tile.setId(View.generateViewId()); tile.setFocusable(true); tile.setClickable(true); tile.setBackground(glass(false,14));
        tile.setOnFocusChangeListener((v,focused)-> {
            v.setBackground(glass(focused,14));animateFocus(v,focused,1.012f);
        });
    }
    private void activate(JSONObject item) {
        if(item.optBoolean("api")){
            String key=item.optString("key");
            if(!key.isEmpty())loadData(key,item.optString("title"),true);
            else if(!item.optString("context").isEmpty())playData("play("+JSONObject.quote(item.optString("context"))+","+item.optInt("index")+",null)");
            else playData("playItem("+item.toString()+")");
            return;
        }
        if (!isMusic(engine.getUrl())) {toast("Apple Music is still connecting.");return;}
        popupAllowedUntil=SystemClock.elapsedRealtime()+15000;
        call("action("+JSONObject.quote(item.optString("id"))+","+JSONObject.quote(item.optString("url"))+","+JSONObject.quote(item.optString("kind"))+")", result-> {
            String value=scalar(result);
            if(value.equals("navigate")){waitingForPage=Uri.parse(item.optString("url")).getPath();loading.setVisibility(View.VISIBLE);loadStarted=SystemClock.elapsedRealtime();refreshUntil=loadStarted+8000;lastSnapshot=0;}
            else if(value.equals("refresh")){lastModel="";lastSnapshot=0;}
            else if(value.equals("stale")||value.isEmpty()) {
                String url=item.optString("url");
                if(isMusic(url))navigate(url,item.optString("title"));
                else {toast("Refreshing this item from Apple Music…");lastSnapshot=0;}
            }
        });
    }

    private void showLibrary() {
        activeDataKey="library-hub";restorePage();
        libraryScreen=true;loading.setVisibility(View.GONE);page.removeAllViews();lastModel="";
        page.addView(label("Your library",30,TEXT));
        TextView hint=label("Playlists and albums saved to your Apple Music account.",16,MUTED);
        hint.setPadding(0,dp(12),0,dp(22));page.addView(hint);
        String[] names={"Your playlists","Saved albums","Recently added","Songs"};
        String[] routes={"all-playlists","albums","recently-added","songs"};
        String[] descriptions={"The mixes you made. The music you love.","Your collection, beautifully organised.","Fresh additions to your library.","Every track, ready when you are."};
        List<View> tiles=new ArrayList<>();LinearLayout row=null;
        for(int i=0;i<names.length;i++){
            if(i%2==0){row=new LinearLayout(this);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(128));rp.bottomMargin=dp(14);page.addView(row,rp);}
            final String route=routes[i],name=names[i];LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setPadding(dp(22),dp(15),dp(22),dp(14));focusTile(tile);tile.setContentDescription(name);tile.setLayerType(View.LAYER_TYPE_HARDWARE,null);
            ImageView icon=new ImageView(this);icon.setImageDrawable(new LibraryIcon(i,dp(28)));tile.addView(icon,new LinearLayout.LayoutParams(dp(28),dp(28)));
            TextView title=label(name,19,TEXT);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));title.setPadding(0,dp(8),0,dp(5));tile.addView(title);
            TextView caption=label(descriptions[i],11,MUTED);caption.setMaxLines(1);caption.setEllipsize(TextUtils.TruncateAt.END);tile.addView(caption);
            tile.setOnClickListener(v->loadData("library/"+(route.equals("all-playlists")?"playlists":route),name,true));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);if(i%2==0)lp.rightMargin=dp(14);row.addView(tile,lp);tiles.add(tile);
        }
        for(int i=0;i<tiles.size();i++){
            View tile=tiles.get(i);tile.setNextFocusLeftId(i%2==0?selected.getId():tiles.get(i-1).getId());
            tile.setNextFocusRightId(i%2==0?tiles.get(i+1).getId():tile.getId());
            tile.setNextFocusUpId(i>=2?tiles.get(i-2).getId():selected.getId());
            tile.setNextFocusDownId(i<2?tiles.get(i+2).getId():playButton.getId());
        }
        if(!tiles.isEmpty())selected.setNextFocusRightId(tiles.get(0).getId());
        status.setText("Choose playlists, albums or songs");
    }

    private void showSearch() {
        activeDataKey="search-input";restorePage();
        libraryScreen=false;searchScreen=true;page.removeAllViews();lastModel="";loading.setVisibility(View.GONE);status.setText("Search Apple Music");
        page.addView(label("Find your next favourite.",28,TEXT));
        EditText query=new EditText(this);query.setTextColor(TEXT);query.setHintTextColor(MUTED);query.setHint("Artists, songs, albums, playlists");query.setSingleLine(true);query.setTextSize(18);query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);query.setPadding(dp(18),0,dp(18),0);query.setBackground(round(PANEL,10,0));
        LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(-1,dp(60));q.topMargin=dp(28);page.addView(query,q);
        Runnable search=()->{String text=query.getText().toString().trim();if(text.isEmpty())return;((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(),0);loadData("search|"+text,"Search",true);};
        query.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER)){search.run();return true;}return false;});
        Button go=control("Search","Search Apple Music");go.setTextSize(16);go.setOnClickListener(v->search.run());LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(dp(140),dp(48));gp.topMargin=dp(18);page.addView(go,gp);
        query.setId(View.generateViewId());navigation.get(0).setNextFocusRightId(query.getId());query.requestFocus();
    }

    private void renderNowPlaying() {
        restorePage();page.removeAllViews();loading.setVisibility(View.GONE);status.setText("Now playing");
        largeTrackArtwork=artView();LinearLayout.LayoutParams cover=new LinearLayout.LayoutParams(dp(250),dp(250));cover.gravity=Gravity.CENTER_HORIZONTAL;cover.topMargin=dp(16);page.addView(largeTrackArtwork,cover);artworks.load(playback.optString("artwork"),largeTrackArtwork,640);
        largeTrackTitle=label(playback.optString("title","Nothing playing"),25,TEXT);largeTrackTitle.setGravity(Gravity.CENTER);largeTrackTitle.setMaxLines(2);LinearLayout.LayoutParams t=new LinearLayout.LayoutParams(-1,-2);t.topMargin=dp(22);page.addView(largeTrackTitle,t);
        largeTrackArtist=label(playback.optString("artist"),15,MUTED);largeTrackArtist.setGravity(Gravity.CENTER);page.addView(largeTrackArtist);
        seekBar=new SeekBar(this);seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));seekBar.setMax((int)playback.optDouble("duration"));seekBar.setProgress((int)playback.optDouble("position"));seekBar.setKeyProgressIncrement(15);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(52));sp.topMargin=dp(18);page.addView(seekBar,sp);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){seeking=true;}public void onStopTrackingTouch(SeekBar s){seeking=false;call("seek("+s.getProgress()+")",null);}public void onProgressChanged(SeekBar s,int value,boolean user){if(user&&!seeking)call("seek("+value+")",null);}});
        playButton.requestFocus();
    }

    private void skeleton(String title) {
        page.removeAllViews();page.addView(label(title,30,TEXT));LinearLayout row=new LinearLayout(this);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(170));rp.topMargin=dp(32);page.addView(row,rp);
        for(int i=0;i<4;i++){View block=new View(this);block.setBackground(round(PANEL,12,0));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.rightMargin=dp(16);row.addView(block,lp);}
    }
    private void empty(String title,String message) {
        page.removeAllViews();page.addView(label(title,28,TEXT));TextView text=label(message,16,MUTED);text.setLineSpacing(dp(4),1);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(24);page.addView(text,lp);
        Button signin=control("Apple account","Apple account");signin.setTextSize(16);signin.setOnClickListener(v->signIn());LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(190),dp(48));bp.topMargin=dp(26);page.addView(signin,bp);
    }

    private void signIn() {
        if(engine==null){toast("Apple Music is starting. Try again in a moment.");return;}
        popupAllowedUntil=SystemClock.elapsedRealtime()+20000;showWeb("Sign in with Apple. D-pad moves the pointer; OK selects; Back returns.");
        call("signIn()",result->{if(scalar(result).equals("unavailable"))call("appleMenu()",null);});
    }
    private void showWeb(String hint) {
        if(engine==null)return;
        webMode=true;nowScreen=false;searchScreen=false;libraryScreen=false;pointer=true;rail.setVisibility(View.GONE);nowBar.setVisibility(View.GONE);nativeContent.setVisibility(View.GONE);engine.getSettings().setBlockNetworkImage(false);engine.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);engine.setAlpha(1);engine.setFocusable(true);engine.requestFocus();authHint.setText(hint);authHint.setVisibility(View.VISIBLE);resetPointer();
    }
    private void hideWeb() {
        if(engine==null)return;webMode=false;pointer=false;rail.setVisibility(View.VISIBLE);nowBar.setVisibility(View.VISIBLE);engine.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);engine.setAlpha(.01f);engine.setFocusable(false);nativeContent.setVisibility(View.VISIBLE);authHint.setVisibility(View.GONE);cursor.setVisibility(View.GONE);CookieManager.getInstance().flush();lastSnapshot=0;lastModel="";refreshUntil=SystemClock.elapsedRealtime()+15000;
    }

    private void settings() {
        String[] entries={"Audio quality","Playback support","Apple account","Reload Apple Music","Storefront / country","Open Apple Player","Remote help","Sign out and clear account data"};
        new AlertDialog.Builder(this).setTitle("Banana Music settings").setItems(entries,(d,which)->{
            if(which==0)qualitySettings();
            else if(which==1)diagnostics();
            else if(which==2)signIn();
            else if(which==3){if(engine==null){toast("Apple Music is starting.");return;}lastModel="";lastSnapshot=0;loading.setVisibility(View.VISIBLE);loadStarted=SystemClock.elapsedRealtime();engine.reload();}
            else if(which==4)storefrontSettings();
            else if(which==5)showWeb("Apple Player · Hold OK to switch pointer / focus mode. Back returns to Banana Music.");
            else if(which==6)new AlertDialog.Builder(this).setTitle("Made for your remote").setMessage("D-pad: move between native cards and controls.\nOK: open an item or play a song.\nBack: return to the previous screen.\n\nApple sign-in uses a pointer: D-pad moves it, OK selects. The TV keyboard handles typing. Hold OK to change pointer / focus mode.\n\nPlay/pause and next/previous keys work if your remote has them. Playback pauses when you leave Banana Music.").setPositiveButton("Got it",null).show();
            else clearAccount();
        }).show();
    }
    private void qualitySettings() {
        boolean high=preferences.getBoolean("highQuality",true);
        new AlertDialog.Builder(this).setTitle("Audio quality").setSingleChoiceItems(new String[]{"High — prefer 256 kbps AAC","Data saver — prefer 64 kbps AAC"},high?0:1,(d,index)->{
            preferences.edit().putBoolean("highQuality",index==0).apply();call("quality("+(index==0)+")",result->toast("true".equals(result)?"Quality preference saved. Applies to the next song.":"Preference saved. It will apply when Apple Music connects."));d.dismiss();
        }).setNeutralButton("Quality details",(d,w)->new AlertDialog.Builder(this).setTitle("What High means").setMessage("High requests Apple’s documented 256 kbps target. Actual delivery depends on Apple and your connection. Changes apply to the next song.\n\nThis playback path has no documented lossless or Dolby Atmos setting.").setPositiveButton("OK",null).show()).setNegativeButton("Close",null).show();
    }
    private void storefrontSettings() {
        EditText country=new EditText(this);country.setSingleLine();country.setText(storefront);country.setHint("Two-letter code: us, gb, in…");
        new AlertDialog.Builder(this).setTitle("Apple Music country").setMessage("Match the country used by your Apple Music account.").setView(country).setPositiveButton("Save",(d,w)->{
            String value=country.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            if(!value.matches("[a-z]{2}")){toast("Enter a two-letter country code.");return;}
            storefront=value;preferences.edit().putString("storefront",value).remove("newCache").apply();catalog.clear();dataCall("clear()",null);dataCall("setCountry("+JSONObject.quote(value)+")",null);dataCall("prefetch()",null);loadData("new","New",false);
        }).setNegativeButton("Cancel",null).show();
    }
    private void diagnostics() {
        call("JSON.stringify(AuralTV.capabilities())",result->{JSONObject c=object(result);boolean drm=MediaDrm.isCryptoSchemeSupported(UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"));
            String version=WebView.getCurrentWebViewPackage()==null?"unknown":WebView.getCurrentWebViewPackage().versionName;
            String message="Android "+Build.VERSION.RELEASE+" · WebView "+version+"\nApple player: "+(playback.optBoolean("ready")?"connected":"waiting")+"\nApple sign-in: "+(playback.optBoolean("needsSignIn",true)?"needed":"no sign-in prompt detected")+"\nProtected playback API: "+(c!=null&&c.optBoolean("encryptedMedia")?"present":"not available")+"\nWidevine on device: "+(drm?"present":"not available")+"\nQuality controls: "+(playback.optBoolean("qualityAvailable")?"available":"waiting")+"\n\nThese checks do not prove subscription playback. Play a song past 30 seconds to confirm. If playback fails, update Android System WebView and check your Apple sign-in.";
            new AlertDialog.Builder(this).setTitle("Playback support").setMessage(message).setPositiveButton("OK",null).show();
        });
    }
    private void clearAccount() {
        if(engine==null){toast("Apple Music is starting.");return;}
        new AlertDialog.Builder(this).setTitle("Clear Apple sign-in?").setMessage("This removes this app’s website sessions and cached catalog. You will need to sign in again.").setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{
            catalog.clear();dataCall("clear()",null);dataHistory.clear();media("pause");CookieManager.getInstance().removeAllCookies(done->{CookieManager.getInstance().flush();android.webkit.WebStorage.getInstance().deleteAllData();engine.clearCache(true);engine.clearHistory();preferences.edit().remove("newCache").apply();model=null;lastModel="";lastSnapshot=0;lastArtwork=null;nowScreen=false;searchScreen=false;waitingForPage="";playback=new JSONObject();updatePlayback();skeleton("New");selected=navigation.get(2);updateRail();loading.setVisibility(View.VISIBLE);loadStarted=SystemClock.elapsedRealtime();engine.loadUrl("https://music.apple.com/"+storefront+"/new");});
        }).show();
    }

    private void call(String expression,Consumer<String> callback) {
        if(engine==null||!isMusic(engine.getUrl())){if(callback!=null)callback.accept("null");return;}
        String script=expression.startsWith("JSON.stringify(")?expression:"AuralTV."+expression;
        engine.evaluateJavascript("window.AuralTV ? ("+script+") : null",result->{if(callback!=null)callback.accept(result);});
    }
    private JSONObject object(String result) {try{Object value=new JSONTokener(result).nextValue();if(value instanceof String)return new JSONObject((String)value);if(value instanceof JSONObject)return (JSONObject)value;}catch(Exception ignored){}return null;}
    private String scalar(String value) {try{Object v=new JSONTokener(value).nextValue();return v==JSONObject.NULL?"":String.valueOf(v);}catch(Exception ignored){return "";}}

    private void createAudioSession() {
        // Chromium's AudioFocusDelegate owns focus for the WebView audio stream.
        // A second native focus owner would pause that stream when either owner gains focus.
        mediaSession=new MediaSession(this,"AuralTV");mediaSession.setCallback(new MediaSession.Callback(){@Override public void onPlay(){media("play");}@Override public void onPause(){media("pause");}@Override public void onSkipToNext(){media("next");}@Override public void onSkipToPrevious(){media("previous");}@Override public void onSeekTo(long ms){call("seek("+(ms/1000)+")",null);}});mediaSession.setActive(true);
        mediaSession.setPlaybackToLocal(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
    }
    private void media(String action){popupAllowedUntil=SystemClock.elapsedRealtime()+10000;call("media("+JSONObject.quote(action)+")",result->{if(!"true".equals(result))toast("Select a song first, or sign in with Apple.");});}
    private void updatePlayback() {
        int requestedQuality=preferences.getBoolean("highQuality",true)?256:64;
        if(playback.optBoolean("qualityAvailable")&&playback.optInt("bitrateTarget")!=requestedQuality)call("quality("+(requestedQuality==256)+")",null);
        if(playback.optBoolean("modesReady")){int shuffle=playback.optInt("shuffle"),repeat=playback.optInt("repeat");if(shuffle!=shuffleMode||repeat!=repeatMode){shuffleMode=shuffle;repeatMode=repeat;preferences.edit().putBoolean("shuffle",shuffle==1).putInt("repeat",repeat).apply();shuffleIcon.setMode(shuffle);repeatIcon.setMode(repeat);}shuffleButton.setContentDescription(shuffle==1?"Shuffle on":"Shuffle off");repeatButton.setContentDescription(repeat==0?"Repeat off":repeat==1?"Repeat one":"Repeat all");}
        playing=playback.optBoolean("playing");playIcon.setMode(playing?1:0);playButton.setContentDescription(playing?"Pause":"Play");
        String title=playback.optString("title"),artist=playback.optString("artist");setTextIfChanged(trackTitle,title.isEmpty()?"Your music. Your subscription.":title);setTextIfChanged(trackArtist,artist.isEmpty()?(!playback.optBoolean("ready")?"Connecting to Apple Music…":playback.optBoolean("needsSignIn")?"Sign in with Apple to get started":"Ready to play"):artist);
        String art=playback.optString("artwork");if(!art.equals(lastArtwork)){lastArtwork=art;trackArtwork.setImageDrawable(null);artworks.load(art,trackArtwork,100);if(nowScreen&&largeTrackArtwork!=null)artworks.load(art,largeTrackArtwork,640);}
        if(nowScreen&&largeTrackTitle!=null){setTextIfChanged(largeTrackTitle,title.isEmpty()?"Nothing playing":title);setTextIfChanged(largeTrackArtist,artist);}
        if(seekBar!=null&&!seeking){seekBar.setMax((int)playback.optDouble("duration"));seekBar.setProgress((int)playback.optDouble("position"));}
        String sessionState=playing+":"+playback.optDouble("position");
        if(!sessionState.equals(lastSessionState)){lastSessionState=sessionState;mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SEEK_TO).setState(playing?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,(long)(playback.optDouble("position")*1000),playing?1:0).build());}
        String metadataKey=title+"\n"+artist+"\n"+playback.optDouble("duration");
        if(!metadataKey.equals(lastMetadataKey)){lastMetadataKey=metadataKey;mediaSession.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title).putString(MediaMetadata.METADATA_KEY_ARTIST,artist).putLong(MediaMetadata.METADATA_KEY_DURATION,(long)(playback.optDouble("duration")*1000)).build());}
        String error=playback.optString("error");if(!error.isEmpty()&&!error.equals(lastError))toast(error);lastError=error;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event){return remote(event)||super.dispatchKeyEvent(event);}
    private boolean remote(KeyEvent event) {
        int code=event.getKeyCode();boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
        if(code==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE||code==KeyEvent.KEYCODE_MEDIA_PLAY||code==KeyEvent.KEYCODE_MEDIA_PAUSE||code==KeyEvent.KEYCODE_MEDIA_NEXT||code==KeyEvent.KEYCODE_MEDIA_PREVIOUS){if(!down)media(code==KeyEvent.KEYCODE_MEDIA_NEXT?"next":code==KeyEvent.KEYCODE_MEDIA_PREVIOUS?"previous":code==KeyEvent.KEYCODE_MEDIA_PLAY?"play":code==KeyEvent.KEYCODE_MEDIA_PAUSE?"pause":"toggle");return true;}
        if(code==KeyEvent.KEYCODE_MENU){if(!down){if(webMode||popup!=null){pointer=!pointer;resetPointer();toast(pointer?"Pointer mode":"Focus mode");}else settings();}return true;}
        if(code==KeyEvent.KEYCODE_BACK){if(!down)back();return true;}
        if(!(webMode||popup!=null)||keyboardVisible())return false;
        WebView active=popupWeb==null?engine:popupWeb;
        if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){
            if(down&&event.getRepeatCount()==0){selecting=true;longSelect=false;handler.postDelayed(()->{if(selecting&&(webMode||popup!=null)){longSelect=true;pointer=!pointer;resetPointer();toast(pointer?"Pointer mode":"Focus mode");}},700);}
            if(!down){selecting=false;if(!longSelect){if(pointer)tap(active);else active.evaluateJavascript("window.AuralTV ? AuralTV.activate() : null",null);}}return true;
        }
        if(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT||code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN){
            if(!down)return true;
            if(pointer){float step=dp(event.getRepeatCount()>4?28:16);int width=active.getWidth(),height=active.getHeight();pointerX=Math.max(dp(12),Math.min(width-dp(12),pointerX+(code==KeyEvent.KEYCODE_DPAD_LEFT?-step:code==KeyEvent.KEYCODE_DPAD_RIGHT?step:0)));pointerY=Math.max(dp(56),Math.min(height-dp(12),pointerY+(code==KeyEvent.KEYCODE_DPAD_UP?-step:code==KeyEvent.KEYCODE_DPAD_DOWN?step:0)));if((pointerY>=height-dp(15)&&code==KeyEvent.KEYCODE_DPAD_DOWN)||(pointerY<=dp(58)&&code==KeyEvent.KEYCODE_DPAD_UP))active.scrollBy(0,code==KeyEvent.KEYCODE_DPAD_DOWN?dp(70):-dp(70));placeCursor();}
            else {String dir=code==KeyEvent.KEYCODE_DPAD_LEFT?"left":code==KeyEvent.KEYCODE_DPAD_RIGHT?"right":code==KeyEvent.KEYCODE_DPAD_UP?"up":"down";active.evaluateJavascript("window.AuralTV ? AuralTV.move('"+dir+"') : null",null);}return true;
        }
        return false;
    }
    private boolean keyboardVisible(){View view=popup==null?getWindow().getDecorView():popup.getWindow().getDecorView();Rect rect=new Rect();view.getWindowVisibleDisplayFrame(rect);return view.getRootView().getHeight()-rect.bottom>dp(130);}
    private void tap(WebView view){long time=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,pointerX,pointerY,0);MotionEvent up=MotionEvent.obtain(time,time+30,MotionEvent.ACTION_UP,pointerX,pointerY,0);view.dispatchTouchEvent(down);view.dispatchTouchEvent(up);down.recycle();up.recycle();}
    private View createCursor(FrameLayout frame){View v=new View(this);v.setBackground(round(0xeeffffff,9,ACCENT));v.setElevation(dp(24));FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(18),dp(18));frame.addView(v,lp);v.setVisibility(View.GONE);v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return v;}
    private void resetPointer(){FrameLayout frame=popupStage==null?stage:popupStage;frame.post(()->{pointerX=frame.getWidth()/2f;pointerY=frame.getHeight()/2f;placeCursor();});}
    private void placeCursor(){View view=popupCursor==null?cursor:popupCursor;view.setVisibility(pointer?View.VISIBLE:View.GONE);view.setTranslationX(pointerX-dp(9));view.setTranslationY(pointerY-dp(9));}
    private void back(){
        if(popup!=null){popup.dismiss();return;}
        if(webMode){hideWeb();dataCall("prefetch()",null);selected.requestFocus();return;}
        if(nowScreen){nowScreen=false;if(catalog.get(activeDataKey)!=null)displayData(catalog.get(activeDataKey));else showLibrary();return;}
        if(!dataHistory.isEmpty()){String key=dataHistory.remove(dataHistory.size()-1);if(key.equals("library-hub"))showLibrary();else if(key.equals("search-input"))showSearch();else loadData(key,"Your music",false);return;}
        if(getCurrentFocus()!=selected){selected.requestFocus();return;}
        new AlertDialog.Builder(this).setTitle("Leave Banana Music?").setMessage("Playback will pause.").setNegativeButton("Stay",null).setPositiveButton("Exit",(d,w)->finish()).show();
    }
    @Override public void onBackPressed(){back();}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)immersive();}
    private void immersive(){getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
    @Override protected void onResume(){super.onResume();alive=true;if(engine!=null)engine.onResume();else handler.postDelayed(this::initializeEngine,250);handler.removeCallbacks(poll);handler.post(poll);if(mediaSession!=null)mediaSession.setActive(true);}
    @Override protected void onStop(){super.onStop();alive=false;handler.removeCallbacks(poll);if(engine!=null){call("media('pause')",null);CookieManager.getInstance().flush();}if(mediaSession!=null)mediaSession.setActive(false);}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level>=TRIM_MEMORY_RUNNING_LOW)artworks.trim();}
    @Override protected void onDestroy(){alive=false;handler.removeCallbacksAndMessages(null);if(popup!=null)popup.dismiss();if(engine!=null){engine.stopLoading();engine.destroy();}if(mediaSession!=null)mediaSession.release();artworks.close();if(catalog!=null)catalog.close();super.onDestroy();}

    private Button navButton(String text){Button b=control(text,text);b.setTextSize(14);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setPadding(dp(14),0,dp(8),0);b.setBackground(round(0x00000000,14,0));b.setOnFocusChangeListener((v,f)->{updateRail();if(!navigation.contains(b))b.setBackground(f?glass(true,14):round(0x00000000,14,0));animateFocus(v,f,1.01f);});return b;}
    private Button transport(PlaybackIcon icon,String description){Button b=new PlaybackButton(this,icon);b.setId(View.generateViewId());b.setFocusableInTouchMode(true);b.setContentDescription(description);b.setStateListAnimator(null);b.setElevation(0);b.setBackground(glass(false,16));b.setOnFocusChangeListener((v,f)->{v.setBackground(glass(f,16));animateFocus(v,f,1.02f);});return b;}
    private Button control(String text,String description){Button b=new Button(this);b.setId(View.generateViewId());b.setFocusableInTouchMode(true);b.setText(text);b.setContentDescription(description);b.setAllCaps(false);b.setTextColor(TEXT);b.setTextSize(22);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(8),0,dp(8),0);b.setStateListAnimator(null);b.setElevation(0);b.setBackground(glass(false,16));b.setOnFocusChangeListener((v,f)->{v.setBackground(glass(f,16));animateFocus(v,f,1.02f);});return b;}
    private void updateRail(){for(Button b:navigation){int state=b.hasFocus()?2:b==selected?1:0;if(Integer.valueOf(state).equals(b.getTag()))continue;b.setTag(state);b.setBackground(state==2?glass(true,14):state==1?glass(false,14):round(0x00000000,14,0));}}
    private LinearLayout.LayoutParams navParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(38));p.bottomMargin=dp(4);return p;}
    private TextView label(String text,float size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");return v;}
    private ImageView artView(){ImageView v=new ImageView(this);v.setScaleType(ImageView.ScaleType.CENTER_CROP);v.setBackground(round(0xff30303a,9,0));v.setClipToOutline(true);return v;}
    private GradientDrawable round(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(2),stroke);return d;}
    private GradientDrawable glass(boolean focused,int radius){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,focused?new int[]{0xf03d2a36,0xef27242f}:new int[]{0xe92b2c36,0xe51d1e27});
        d.setCornerRadius(dp(radius));d.setStroke(dp(focused?1.5f:.5f),focused?0xfffa5376:0x28ffffff);return d;
    }
    private void animateFocus(View view,boolean focused,float scale){view.animate().cancel();view.animate().scaleX(focused?scale:1f).scaleY(focused?scale:1f).setDuration(120).setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f)).start();}
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void setTextIfChanged(TextView view,String text){if(!TextUtils.equals(view.getText(),text))view.setText(text);}
    private boolean under(View root,View child){while(child!=null){if(child==root)return true;android.view.ViewParent parent=child.getParent();child=parent instanceof View?(View)parent:null;}return false;}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
}
