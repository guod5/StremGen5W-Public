package com.gen5.stremio;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import javax.net.ssl.*;
import java.security.*;
import java.util.zip.GZIPInputStream;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.security.MessageDigest;

/** Android 4.4 native client. All network operations run off the UI thread. */
public class MainActivity extends Activity {
  final int bg=0xff0b0d12, accent=0xff13b7a6, surface=0xff191e28, muted=0xffaeb8c5;
  LinearLayout root,body; String page="Home",auth="",selectedId="",selectedType="movie";
  ArrayList<String> history=new ArrayList<String>(); JSONArray addons=new JSONArray(); JSONArray libraryItems=new JSONArray(); boolean returningFromPlayer=false; int generation=0; ArrayList<String> errors=new ArrayList<String>(); boolean routeVerified=false; String selectedName=""; final String CINEMETA="https://v3-cinemeta.strem.io/";
  float sx,sy; android.content.SharedPreferences prefs;
  // Persistent preferences: motion lockout defaults OFF; reminder defaults ON.
  // The acknowledgement is remembered only until the app is explicitly closed.
  boolean parkedConfirmed=false,warningShown=false,mainResumed=false,closingApp=false;
  boolean motionLocked=false;
  String motionReason="Motion lockout is off.";
  Dialog parkedDialog,motionDialog;
  TextView motionDialogText,motionStatusText;
  MotionGuard motionGuard;
  final java.util.HashMap<String,String> artworkCache=new java.util.HashMap<String,String>();
  int posterLoaded=0,posterFailed=0,posterMissing=0; String lastPosterError="";
  String verifiedAccountEmail=""; boolean accountIdentityLoading=false;
  String lastAccountAudit="Not checked. Tap the account audit button below.";
  String lastLibrarySource="Local cache";
  final ExecutorService apiWorkers=Executors.newFixedThreadPool(4);
  // Catalogs have dedicated queues so a slow add-on cannot block account and stream requests.
  final ExecutorService catalogWorkers=Executors.newFixedThreadPool(5);
  final ExecutorService sportsWorkers=Executors.newFixedThreadPool(3);
  final ExecutorService imageWorkers=Executors.newFixedThreadPool(4);
  final LruCache<String,Bitmap> memoryPosters=new LruCache<String,Bitmap>(8192){
    protected int sizeOf(String key,Bitmap value){return Math.max(1,value.getByteCount()/1024);}
  };
  final HashMap<String,ArrayList<WeakReference<ImageView>>> pendingImages=new HashMap<String,ArrayList<WeakReference<ImageView>>>();
  final HashMap<String,JSONObject> catalogMemory=new HashMap<String,JSONObject>();
  final HashMap<String,Long> catalogFetched=new HashMap<String,Long>();
  final HashSet<String> catalogPending=new HashSet<String>();
  final HashMap<String,ArrayList<WeakReference<LinearLayout>>> catalogWaiters=new HashMap<String,ArrayList<WeakReference<LinearLayout>>>();
  final HashMap<String,Long> recentlyBadPosters=new HashMap<String,Long>();
  final HashMap<String,JSONObject> streamMemory=new HashMap<String,JSONObject>();
  final HashSet<String> streamPending=new HashSet<String>();
  final LinkedHashMap<String,JSONObject> visibleSources=new LinkedHashMap<String,JSONObject>();
  LinearLayout sourcePanel;
  TextView sourceStatus;
  String sourceFilter="All", visibleStreamTitle="";
  final LinkedHashMap<String,Button> qualityButtons=new LinkedHashMap<String,Button>();
  int sourceRequests=0;
  long lastAddonSync=0,lastLibrarySync=0;
  boolean addonsLoading=false,libraryLoading=false;
  static final long CATALOG_AGE=30*60*1000L,LIBRARY_AGE=90*1000L,ADDONS_AGE=20*60*1000L;
  String dateNow(){return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",java.util.Locale.US){{setTimeZone(java.util.TimeZone.getTimeZone("UTC"));}}.format(new Date());}
  String fileKey(String key){
    try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));
      StringBuilder out=new StringBuilder();for(int i=0;i<digest.length;i++){String x=Integer.toHexString(digest[i]&255);if(x.length()==1)out.append('0');out.append(x);}return out.toString();
    }catch(Exception e){return Integer.toHexString(key.hashCode());}
  }
  File dataFile(String group,String key){File dir=new File(getCacheDir(),group);if(!dir.exists())dir.mkdirs();return new File(dir,fileKey(key)+".json");}
  File posterFile(String url){File dir=new File(getCacheDir(),"posters");if(!dir.exists())dir.mkdirs();return new File(dir,fileKey(url)+".img");}
  byte[] bytes(File file,int limit)throws IOException{
    FileInputStream in=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
    try{while((n=in.read(buf))!=-1){output.write(buf,0,n);if(output.size()>limit)throw new IOException("Cache item too large");}}
    finally{in.close();}return output.toByteArray();
  }
  void saveBytes(File target,byte[] data){
    if(data==null||data.length==0)return;
    try{File temp=new File(target.getAbsolutePath()+".tmp");FileOutputStream out=new FileOutputStream(temp);try{out.write(data);}finally{out.close();}
      if(!temp.renameTo(target)){if(target.exists())target.delete();temp.renameTo(target);}
    }catch(Exception ignored){}
  }
  JSONObject readJson(File file){try{return new JSONObject(new String(bytes(file,2*1024*1024),"UTF-8"));}catch(Exception ignored){return null;}}
  void cacheJson(File file,JSONObject item){try{saveBytes(file,item.toString().getBytes("UTF-8"));}catch(Exception ignored){}}
  File libraryFile(){return dataFile("account", "library:"+fileKey(auth));}
  File addonsFile(){return dataFile("account", "addons:"+fileKey(auth));}
  void restoreAccountCache(){
    if(auth.isEmpty())return;
    JSONObject a=readJson(addonsFile());if(a!=null){JSONArray arr=a.optJSONArray("addons");if(arr!=null)addons=arr;}
    JSONObject l=readJson(libraryFile());if(l!=null){JSONArray arr=l.optJSONArray("items");if(arr!=null){libraryItems=arr;lastLibrarySource="Local cache • "+arr.length()+" items";}}
  }
  void saveLibraryCache(){
    if(auth.isEmpty())return;try{JSONObject data=new JSONObject();data.put("items",libraryItems);cacheJson(libraryFile(),data);}catch(Exception ignored){}
  }
  void saveAddonCache(){
    if(auth.isEmpty())return;try{JSONObject data=new JSONObject();data.put("addons",addons);cacheJson(addonsFile(),data);}catch(Exception ignored){}
  }
  int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
  public void onCreate(Bundle b){super.onCreate(b);prefs=getSharedPreferences("account",0);
    parkedConfirmed=prefs.getBoolean("disable_popup",false);
    auth=prefs.getString("auth","");verifiedAccountEmail=prefs.getString("verified_account_email","");
    restoreAccountCache();
    selectedId=b==null?prefs.getString("selected_id",""):b.getString("selected_id",prefs.getString("selected_id",""));
    selectedType=b==null?prefs.getString("selected_type","movie"):b.getString("selected_type","movie");
    selectedName=b==null?prefs.getString("selected_name",""):b.getString("selected_name","");
    immersiveWindow();
    String restore=b==null?"":b.getString("previous_page","");
    if((prefs.getBoolean("external_playback",false)||"Streams".equals(restore)||"Player".equals(restore)||"Streams".equals(prefs.getString("last_page","")))&&!selectedId.isEmpty())
      render("Streams",false);else render("Home",false);
    if(!auth.isEmpty()){refreshAccountIdentity();refreshAddons();refreshLibrary(false);}
    showParkedConfirmation();
  }
  void showParkedConfirmation(){
    if(prefs!=null&&prefs.getBoolean("disable_popup",false)){parkedConfirmed=true;return;}
    if(parkedConfirmed||warningShown||closingApp||isFinishing()||
      (parkedDialog!=null&&parkedDialog.isShowing()))return;
    warningShown=true;
    final Dialog dialog=new Dialog(this);
    parkedDialog=dialog;
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    dialog.setCancelable(false);
    dialog.setCanceledOnTouchOutside(false);
    LinearLayout panel=new LinearLayout(this);
    panel.setOrientation(1);
    panel.setPadding(dp(19),dp(16),dp(19),dp(16));
    panel.setBackgroundDrawable(shape(surface,17));
    TextView heading=text("PARKED VEHICLE REQUIRED",20);
    heading.setTextColor(accent);
    heading.setTypeface(null,1);
    heading.setPadding(dp(8),dp(1),dp(8),dp(4));
    panel.addView(heading);
    TextView copy=text("Confirm your vehicle is fully parked. Video playback is for use only while parked. Never watch or operate video while driving.",16);
    copy.setTextColor(Color.WHITE);
    copy.setPadding(dp(8),dp(2),dp(8),dp(12));
    panel.addView(copy);
    LinearLayout actions=new LinearLayout(this);
    actions.setOrientation(0);
    Button exit=btn("Exit App",new Runnable(){public void run(){dialog.dismiss();parkedDialog=null;finish();}});
    Button confirm=btn("I'm Parked - Continue",new Runnable(){public void run(){
      parkedConfirmed=true;dialog.dismiss();parkedDialog=null;refreshMotionDialog();
    }});
    exit.setTextSize(15);confirm.setTextSize(15);
    confirm.setBackgroundDrawable(shape(accent,12));
    confirm.setTextColor(0xff071312);
    LinearLayout.LayoutParams exitLayout=new LinearLayout.LayoutParams(0,dp(53),1);
    exitLayout.setMargins(0,0,dp(8),0);
    actions.addView(exit,exitLayout);
    actions.addView(confirm,new LinearLayout.LayoutParams(0,dp(53),2));
    panel.addView(actions);
    dialog.setContentView(panel);
    dialog.show();
    Window window=dialog.getWindow();
    if(window!=null){
      window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
      window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(26),dp(720)),WindowManager.LayoutParams.WRAP_CONTENT);
      window.setGravity(Gravity.CENTER);
      window.getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
  }
  void immersiveWindow(){
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);
    getWindow().getDecorView().setSystemUiVisibility(
      View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
      View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
  }
  @Override public void onWindowFocusChanged(boolean hasFocus){
    super.onWindowFocusChanged(hasFocus);
    if(hasFocus)immersiveWindow();
  }
  @Override protected void onSaveInstanceState(Bundle state){
    state.putString("selected_id",selectedId);state.putString("selected_type",selectedType);
    state.putString("selected_name",selectedName);state.putString("previous_page",page);
    super.onSaveInstanceState(state);
  }
  void rememberTitle(){if(prefs!=null)prefs.edit().putString("selected_id",selectedId).putString("selected_type",selectedType).putString("selected_name",selectedName).apply();}
  void refreshAccountIdentity(){
    if(auth.isEmpty()||accountIdentityLoading)return;
    accountIdentityLoading=true;
    request(new Job(){public JSONObject run()throws Exception{return post("getUser",authJson());}},
      new Result(){public void done(JSONObject j){
        accountIdentityLoading=false;
        JSONObject result=j.optJSONObject("result");
        JSONObject u=result==null?null:result.optJSONObject("user");
        if(u==null)u=result;
        String fromServer=u==null?"":u.optString("email","");
        if(!fromServer.isEmpty()){
          boolean mismatch=!verifiedAccountEmail.isEmpty()&&!verifiedAccountEmail.equalsIgnoreCase(fromServer);
          verifiedAccountEmail=fromServer;
          prefs.edit().putString("verified_account_email",fromServer).apply();
          if(mismatch){libraryItems=new JSONArray();addons=new JSONArray();lastLibrarySync=0;lastAddonSync=0;}
          if(page.equals("Account"))render("Account",false);
        }
      }});
  }
  interface Job { JSONObject run() throws Exception; }
  interface Result { void done(JSONObject j); }
  void request(final Job job, final Result result){apiWorkers.execute(new Runnable(){public void run(){try{final JSONObject r=job.run();runOnUiThread(new Runnable(){public void run(){if(!isFinishing())result.done(r);}});}catch(final Exception e){runOnUiThread(new Runnable(){public void run(){recordError(e);}});}}});}
  // Android 4.4 includes TLS 1.2 but does not enable it by default on all builds.
  // Enable it on supported sockets without weakening normal CA/hostname validation.
  static class Tls12Factory extends SSLSocketFactory {
    final SSLSocketFactory delegate;
    Tls12Factory(SSLSocketFactory factory){delegate=factory;}
    java.net.Socket configure(java.net.Socket socket){
      if(socket instanceof SSLSocket){SSLSocket ssl=(SSLSocket)socket;
        java.util.ArrayList<String> protocols=new java.util.ArrayList<String>();
        for(String protocol:ssl.getSupportedProtocols())if("TLSv1.2".equals(protocol)||"TLSv1.3".equals(protocol))protocols.add(protocol);
        if(!protocols.isEmpty())ssl.setEnabledProtocols(protocols.toArray(new String[protocols.size()]));
      }return socket;
    }
    public String[] getDefaultCipherSuites(){return delegate.getDefaultCipherSuites();}
    public String[] getSupportedCipherSuites(){return delegate.getSupportedCipherSuites();}
    public java.net.Socket createSocket(java.net.Socket s,String h,int p,boolean a)throws IOException{return configure(delegate.createSocket(s,h,p,a));}
    public java.net.Socket createSocket(String h,int p)throws IOException{return configure(delegate.createSocket(h,p));}
    public java.net.Socket createSocket(String h,int p,java.net.InetAddress l,int lp)throws IOException{return configure(delegate.createSocket(h,p,l,lp));}
    public java.net.Socket createSocket(java.net.InetAddress h,int p)throws IOException{return configure(delegate.createSocket(h,p));}
    public java.net.Socket createSocket(java.net.InetAddress h,int p,java.net.InetAddress l,int lp)throws IOException{return configure(delegate.createSocket(h,p,l,lp));}
  }
  // Security: APK-bundled Mozilla trusted CAs for image requests on Android 4.4.
  // DO NOT disable certificate or hostname verification. Stremio account requests
  // continue to use the platform trust manager; only image requests use these roots.
  volatile SSLSocketFactory posterTlsFactory;
  int trustedPosterRoots=0;
  synchronized SSLSocketFactory posterSslFactory()throws Exception{
    if(posterTlsFactory!=null)return posterTlsFactory;
    KeyStore roots=KeyStore.getInstance(KeyStore.getDefaultType());roots.load(null,null);
    InputStream stream=getAssets().open("cacert.pem");
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    byte[] buf=new byte[8192];int size;
    try{while((size=stream.read(buf))!=-1){
      out.write(buf,0,size);if(out.size()>500000)throw new IOException("CA bundle too large");
    }}finally{stream.close();}
    String pem=new String(out.toByteArray(),"US-ASCII");
    String begin="-----BEGIN CERTIFICATE-----",end="-----END CERTIFICATE-----";
    java.security.cert.CertificateFactory factory=java.security.cert.CertificateFactory.getInstance("X.509");
    int cursor=0,count=0;
    while(true){
      int first=pem.indexOf(begin,cursor);if(first<0)break;
      int last=pem.indexOf(end,first);if(last<0)break;
      String body=pem.substring(first+begin.length(),last).replaceAll("\\s+","");
      cursor=last+end.length();
      byte[] der=android.util.Base64.decode(body,android.util.Base64.DEFAULT);
      java.security.cert.Certificate cert=factory.generateCertificate(new ByteArrayInputStream(der));
      roots.setCertificateEntry("mozilla-"+count,cert);count++;
    }
    if(count<80)throw new IOException("Bundled image CA store incomplete");
    TrustManagerFactory managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    managers.init(roots);
    SSLContext context=SSLContext.getInstance("TLS");
    context.init(null,managers.getTrustManagers(),new SecureRandom());
    trustedPosterRoots=count;
    posterTlsFactory=new Tls12Factory(context.getSocketFactory());
    return posterTlsFactory;
  }
  HttpURLConnection imageConnection(String url)throws Exception{
    HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
    if(c instanceof HttpsURLConnection){
      HttpsURLConnection ssl=(HttpsURLConnection)c;
      ssl.setSSLSocketFactory(posterSslFactory());
      // HttpsURLConnection's default hostname verifier is preserved.
    }
    c.setRequestProperty("Accept-Encoding","gzip");
    c.setConnectTimeout(12000);c.setReadTimeout(18000);return c;
  }

  HttpURLConnection connection(String endpoint)throws Exception{
    // Android-selected routing is not verified as phone-only.
    URL target=new URL(endpoint);
    HttpURLConnection c=(HttpURLConnection)target.openConnection();
    if(c instanceof HttpsURLConnection){
      HttpsURLConnection ssl=(HttpsURLConnection)c;
      String host=target.getHost().toLowerCase(Locale.US);
      // Sports Streams uses newer public CAs than this Android 4.4 firmware.
      // Use the same verified Mozilla trust bundle as the working player and
      // artwork downloader. Preserve platform hostname verification and TLS.
      if(host.equals("highfly.to")||host.endsWith(".highfly.to")||
         host.equals("highfly.dev")||host.endsWith(".highfly.dev")){
        ssl.setSSLSocketFactory(VideoTls.buildSports(this));
      }else{
        ssl.setSSLSocketFactory(new Tls12Factory((SSLSocketFactory)SSLSocketFactory.getDefault()));
      }
    }
    c.setRequestProperty("Accept-Encoding","gzip");
    c.setConnectTimeout(15000);c.setReadTimeout(25000);return c;
  }
  InputStream responseStream(HttpURLConnection c,int status)throws Exception{
    InputStream in=status<400?c.getInputStream():c.getErrorStream();
    if(in!=null&&"gzip".equalsIgnoreCase(c.getContentEncoding()))return new GZIPInputStream(in);
    return in;
  }
  JSONObject post(String path,JSONObject payload)throws Exception{
    HttpURLConnection c=connection("https://api.strem.io/api/"+path);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] bytes=payload.toString().getBytes("UTF-8");c.getOutputStream().write(bytes);int status=c.getResponseCode();InputStream stream=responseStream(c,status);String text=read(stream);c.disconnect();if(status>=400)throw new IOException("Stremio API "+path+" HTTP "+status);JSONObject j=new JSONObject(text);if(j.has("error")&&!j.isNull("error"))throw new IOException(j.optString("error"));return j;
  }
  JSONObject get(String url)throws Exception {HttpURLConnection c=connection(url);c.setRequestProperty("Accept","application/json");int status=c.getResponseCode();String t=read(responseStream(c,status));c.disconnect();if(status>=400)throw new IOException("HTTP "+status+" at "+safeEndpoint(url));return new JSONObject(t);}
  String safeEndpoint(String url){try{java.net.URL u=new java.net.URL(url);return u.getProtocol()+"://"+u.getHost()+u.getPath().replaceAll("/[^/]{40,}/","/[private]/");}catch(Exception e){return "endpoint unavailable";}}
  String read(InputStream s)throws Exception{if(s==null)return "";ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=s.read(b))!=-1){o.write(b,0,n);if(o.size()>16000000)throw new IOException("Response exceeded 16 MB safety limit");}s.close();return o.toString("UTF-8");}
  void recordError(Exception e){String t=e.getClass().getSimpleName()+": "+e.getMessage(); if(t.length()>220)t=t.substring(0,220);errors.add(0,t);while(errors.size()>30)errors.remove(errors.size()-1); if(page.equals("Diagnostics"))render("Diagnostics",false);}
  void message(String s){new AlertDialog.Builder(this).setMessage(s).setPositiveButton("OK",null).show();}
  android.graphics.drawable.GradientDrawable shape(int color,int radius){
    android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();
    d.setColor(color);d.setCornerRadius(dp(radius));return d;
  }
  void styleField(EditText e){e.setSingleLine(true);e.setTextColor(Color.WHITE);e.setHintTextColor(muted);e.setTextSize(18);e.setPadding(dp(18),dp(12),dp(18),dp(12));e.setBackgroundDrawable(shape(surface,12));}
  TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(size<=16?muted:Color.WHITE);t.setPadding(dp(16),dp(11),dp(16),dp(11));return t;}
  Button btn(String s,final Runnable r){Button b=new Button(this);b.setText(s);b.setTextSize(16);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setBackgroundDrawable(shape(surface,12));b.setMinHeight(dp(52));b.setPadding(dp(13),dp(8),dp(13),dp(8));b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){r.run();}});return b;}
  void add(View v){body.addView(v,new LinearLayout.LayoutParams(-1,-2));}
  String installedBuild(){
    try{
      String name=getPackageManager().getPackageInfo(getPackageName(),0).versionName;
      return name==null?"unknown":name;
    }catch(Exception e){return "unknown";}
  }
  int installedVersionCode(){
    try{return getPackageManager().getPackageInfo(getPackageName(),0).versionCode;}
    catch(Exception ignored){return -1;}
  }
  String shortBuild(){
    String name=installedBuild();int dash=name.indexOf('-');
    return dash>0?name.substring(0,dash):name;
  }
  void render(String next,boolean push){if(push && !page.equals(next))history.add(page);page=next;
    if(prefs!=null&&("Home".equals(next)||"Streams".equals(next)||"Library".equals(next)||"Search".equals(next)))
      prefs.edit().putString("last_page",next).apply();
    root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(bg);
    LinearLayout header=new LinearLayout(this);header.setOrientation(0);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(12),dp(6),dp(12),dp(6));
    TextView brand=text("STREMIO AUTO",19);brand.setTextColor(accent);brand.setTypeface(null,1);header.addView(brand,new LinearLayout.LayoutParams(0,dp(52),1));
    TextView current=text("v"+shortBuild()+"  •  "+page.toUpperCase(java.util.Locale.US),13);current.setTextColor(accent);header.addView(current);
    root.addView(header);
    LinearLayout nav=new LinearLayout(this);nav.setOrientation(0);nav.setPadding(dp(9),dp(2),dp(9),dp(8));
    String[] names={"‹ Back","Home","Library","Search","Add-ons","Account","Info","Settings"};
    final String[] pages={"Back","Home","Library","Search","Add-ons","Account","Diagnostics","Settings"};
    for(int i=0;i<names.length;i++){
      final String target=pages[i];Button tab=btn(names[i],new Runnable(){public void run(){if(target.equals("Back"))back();else render(target,true);}});
      tab.setTextSize(13);tab.setPadding(dp(2),dp(3),dp(2),dp(3));
      if(target.equals(page)){tab.setBackgroundDrawable(shape(accent,10));tab.setTextColor(0xff071312);}
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(49),1);lp.setMargins(dp(3),0,dp(3),0);nav.addView(tab,lp);
    }
    root.addView(nav);
    ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.setVerticalScrollBarEnabled(false);
    body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(10),dp(4),dp(10),dp(16));sc.addView(body);
    root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    if(page.equals("Home")){home();}
    else if(page.equals("Account"))account();else if(page.equals("Add-ons"))showAddons();else if(page.equals("Library"))loadLibrary();else if(page.equals("Discover")||page.equals("Search"))discover();else if(page.equals("Diagnostics"))diagnostics();else if(page.equals("Settings"))settings();else if(page.equals("Streams"))loadStreams();
    refreshMotionDialog();
  }
  void home(){
    LinearLayout actions=new LinearLayout(this);actions.setOrientation(0);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    TextView homeHeading=text("Your Stremio",20);homeHeading.setTypeface(null,1);
    actions.addView(homeHeading,new LinearLayout.LayoutParams(0,dp(56),1));
    Button exit=btn("Exit Stremio",new Runnable(){public void run(){confirmExitStremio();}});
    exit.setTextSize(15);exit.setBackgroundDrawable(shape(surface,12));
    actions.addView(exit,new LinearLayout.LayoutParams(dp(175),dp(52)));
    add(actions);
    // Home starts immediately with personal content. Search, Library, and
    // Add-ons already have permanent navigation buttons in the top bar.
    showContinueWatching();
    showCatalog("Popular movies",CINEMETA+"catalog/movie/top.json","movie");
    showCatalog("Popular series",CINEMETA+"catalog/series/top.json","series");
    if(auth.isEmpty()){add(text("Sign in under Account to load your personal add-ons and library.",16));return;}
    if(addons.length()==0){add(text("Account add-ons are still loading, or could not be synchronized. Check Diagnostics.",16));return;}
    // Prioritize sport/live-TV results but keep the full installed catalog list.
    HashSet<String> catalogKeys=new HashSet<String>();
    int shown=0,unavailable=0;
    for(int priority=0;priority<2;priority++)for(int i=0;i<addons.length();i++){
      JSONObject addon=addons.optJSONObject(i);if(addon==null)continue;
      JSONObject manifest=addon.optJSONObject("manifest");
      if(manifest==null){if(priority==0)unavailable++;continue;}
      JSONArray cats=manifest.optJSONArray("catalogs");if(cats==null)continue;
      String base=baseUrl(manifestUrl(addon));if(!base.startsWith("https://"))continue;
      String name=manifest.optString("name","Add-on");
      for(int k=0;k<cats.length();k++){
        JSONObject c=cats.optJSONObject(k);if(c==null)continue;
        String type=c.optString("type","movie"),id=c.optString("id","");
        boolean sports=type.equalsIgnoreCase("sport")||name.toLowerCase(Locale.US).contains("sports");
        if((priority==0)!=sports||id.isEmpty()||!catalogKeys.add(base+"|"+type+"|"+id))continue;
        String heading=c.optString("name",id)+" • "+name;
        try{
          StringBuilder url=new StringBuilder(base).append("catalog/").append(enc(type)).append("/").append(enc(id));
          JSONArray extras=c.optJSONArray("extra");
          StringBuilder chosen=new StringBuilder();
          boolean needsSetup=false;
          if(extras!=null)for(int x=0;x<extras.length();x++){
            JSONObject ex=extras.optJSONObject(x);
            if(ex==null||!ex.optBoolean("isRequired",false))continue;
            JSONArray options=ex.optJSONArray("options");
            if(options==null||options.length()==0){needsSetup=true;break;}
            if(chosen.length()>0)chosen.append("&");
            chosen.append(enc(ex.optString("name"))).append("=").append(enc(options.optString(0)));
          }
          if(needsSetup){add(text(heading+" needs a configuration selection in its installed add-on.",14));continue;}
          if(chosen.length()>0)url.append("/").append(chosen);
          showCatalog(heading,url.append(".json").toString(),type);shown++;
        }catch(Exception e){recordError(e);}
      }
    }
    if(unavailable>0)add(text(unavailable+" add-on manifests have not loaded. Refresh Add-ons to retry.",14));
    if(shown==0)add(text("No add-on catalogs available. Open Add-ons and refresh.",14));
  }
  // Settings are intentionally stored independently from account credentials.
  // A NEMESIS clean reinstall still clears app data, including these choices.
  void settings(){
    TextView heading=text("Settings",23);heading.setTypeface(null,1);add(heading);
    add(text("Choose how Stremio behaves on this head unit.",15));

    final Switch motion=new Switch(this);
    motion.setText("Motion lockout");
    motion.setTextColor(Color.WHITE);motion.setTextSize(19);
    motion.setPadding(dp(14),dp(12),dp(12),dp(12));
    motion.setMinHeight(dp(63));
    motion.setChecked(prefs.getBoolean("motion_lockout",false));
    add(motion);
    add(text("Off by default. When enabled, GPS movement at 2 mph or above pauses in-app playback. A stable GPS fix is required; playback stays paused if the GPS signal is unavailable. This does not control VLC.",14));
    motionStatusText=text(motion.isChecked()?motionReason:"Motion lockout is off.",14);
    motionStatusText.setTextColor(accent);add(motionStatusText);
    motion.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){
      public void onCheckedChanged(CompoundButton button,boolean enabled){
        prefs.edit().putBoolean("motion_lockout",enabled).apply();
        updateMotionMonitoring();
        if(motionStatusText!=null)motionStatusText.setText(enabled?motionReason:"Motion lockout is off.");
      }
    });

    View divider=new View(this);divider.setBackgroundColor(0xff323c49);
    LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(1));
    line.setMargins(dp(13),dp(16),dp(13),dp(12));body.addView(divider,line);

    final Switch popup=new Switch(this);
    popup.setText("Disable popup");
    popup.setTextColor(Color.WHITE);popup.setTextSize(19);
    popup.setPadding(dp(14),dp(12),dp(12),dp(12));
    popup.setMinHeight(dp(63));
    popup.setChecked(prefs.getBoolean("disable_popup",false));
    add(popup);
    add(text("Hide the one-time parked-use reminder when launching Stremio. This preference will be saved.",14));
    popup.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){
      public void onCheckedChanged(CompoundButton button,boolean disabled){
        prefs.edit().putBoolean("disable_popup",disabled).apply();
        if(disabled){
          parkedConfirmed=true;
          if(parkedDialog!=null){parkedDialog.dismiss();parkedDialog=null;}
          refreshMotionDialog();
        }else if(!warningShown){
          parkedConfirmed=false;
          showParkedConfirmation();
        }
      }
    });

    View modemDivider=new View(this);modemDivider.setBackgroundColor(0xff323c49);
    LinearLayout.LayoutParams modemLine=new LinearLayout.LayoutParams(-1,dp(1));
    modemLine.setMargins(dp(13),dp(16),dp(13),dp(12));
    body.addView(modemDivider,modemLine);

    final android.content.SharedPreferences networkPrefs=getSharedPreferences("network",0);
    final Switch modemSwitch=new Switch(this);
    modemSwitch.setText("Use Internal Modem");
    modemSwitch.setTextColor(Color.WHITE);modemSwitch.setTextSize(19);
    modemSwitch.setPadding(dp(14),dp(12),dp(12),dp(12));
    modemSwitch.setMinHeight(dp(63));
    modemSwitch.setChecked(networkPrefs.getBoolean("use_internal_modem",false));
    add(modemSwitch);
    add(text("The car's built-in data connection. Usage may be monitored by HMG.",14));
    add(text("OFF (default): Wi-Fi and Bluetooth/USB hotspots only. ON: allow Android to use the car's internal modem, as before the firewall update. The NEMESIS service applies changes within a few seconds; it must be running.",14));
    final boolean[] modemReverting={false};
    modemSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){
      public void onCheckedChanged(CompoundButton button,boolean enabled){
        if(modemReverting[0])return;
        if(!networkPrefs.edit().putBoolean("use_internal_modem",enabled).commit()){
          modemReverting[0]=true;
          modemSwitch.setChecked(!enabled);
          modemReverting[0]=false;
          message("Could not save the modem preference. No change requested.");
          return;
        }
        message(enabled?
          "Internal modem access requested; NEMESIS will remove Stremio's firewall.":
          "Wi-Fi/hotspot-only mode requested; NEMESIS will restore Stremio's firewall.");
      }
    });

    View vlcDivider=new View(this);vlcDivider.setBackgroundColor(0xff323c49);
    LinearLayout.LayoutParams vlcLine=new LinearLayout.LayoutParams(-1,dp(1));
    vlcLine.setMargins(dp(13),dp(16),dp(13),dp(12));body.addView(vlcDivider,vlcLine);

    final Switch vlcSwitch=new Switch(this);
    vlcSwitch.setText("VLC integration");
    vlcSwitch.setTextColor(Color.WHITE);vlcSwitch.setTextSize(19);
    vlcSwitch.setPadding(dp(14),dp(12),dp(12),dp(12));
    vlcSwitch.setMinHeight(dp(63));
    vlcSwitch.setChecked(vlcIntegrationEnabled());
    add(vlcSwitch);
    add(text("Off by default for network safety. The firewall protects Stremio Auto, not VLC. A separate VLC player may use the car's modem.",14));
    final boolean[] vlcAcknowledged={false};
    vlcSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){
      public void onCheckedChanged(CompoundButton button,boolean enabled){
        if(enabled&&!vlcAcknowledged[0]){
          vlcSwitch.setChecked(false);
          new AlertDialog.Builder(MainActivity.this)
            .setTitle("VLC is not network protected")
            .setMessage("Stremio Auto's Wi-Fi/hotspot firewall only protects this app. VLC runs separately and might stream through the car's built-in modem. Leave VLC off to avoid that risk.")
            .setNegativeButton("Keep VLC off",null)
            .setPositiveButton("Enable VLC anyway",new DialogInterface.OnClickListener(){
              public void onClick(DialogInterface dialog,int which){
                vlcAcknowledged[0]=true;
                vlcSwitch.setChecked(true);
                vlcAcknowledged[0]=false;
              }
            }).show();
          return;
        }
        prefs.edit().putBoolean("vlc_integration",enabled).apply();
      }
    });
    add(text("NETWORK FIREWALL",17));
    add(text("Use Internal Modem OFF activates Stremio's Wi-Fi/hotspot firewall. ON removes the restriction and lets Android use its normal route. The NEMESIS service must be running. Android system DNS and external VLC are outside this app's firewall.",14));
  }

  // VLC is outside our app-UID network guard; require explicit user opt-in.
  boolean vlcIntegrationEnabled(){
    return prefs!=null&&prefs.getBoolean("vlc_integration",false);
  }

  void updateMotionMonitoring(){
    boolean enabled=prefs!=null&&prefs.getBoolean("motion_lockout",false);
    if(enabled&&mainResumed&&!closingApp){
      if(motionGuard==null)motionGuard=new MotionGuard(this,new MotionGuard.Listener(){
        public void onMotionStateChanged(boolean locked,String reason){
          motionLocked=locked;
          motionReason=reason;
          if(motionStatusText!=null&&"Settings".equals(page))motionStatusText.setText(reason);
          refreshMotionDialog();
        }
      });
      motionLocked=true;
      motionReason="Waiting for GPS...";
      motionGuard.start();
    }else{
      if(motionGuard!=null)motionGuard.stop();
      motionLocked=false;
      motionReason="Motion lockout is off.";
      if(motionStatusText!=null&&"Settings".equals(page))motionStatusText.setText(motionReason);
    }
    refreshMotionDialog();
  }

  void refreshMotionDialog(){
    boolean show=prefs!=null&&prefs.getBoolean("motion_lockout",false)&&
      motionLocked&&parkedConfirmed&&mainResumed&&!closingApp&&!isFinishing()&&
      !"Settings".equals(page);
    if(!show){
      if(motionDialog!=null){motionDialog.dismiss();motionDialog=null;motionDialogText=null;}
      return;
    }
    if(motionDialog!=null&&motionDialog.isShowing()){
      if(motionDialogText!=null)motionDialogText.setText(motionReason);
      return;
    }
    final Dialog dialog=new Dialog(this);
    motionDialog=dialog;dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    dialog.setCancelable(false);dialog.setCanceledOnTouchOutside(false);
    LinearLayout panel=new LinearLayout(this);panel.setOrientation(1);
    panel.setPadding(dp(19),dp(15),dp(19),dp(16));
    panel.setBackgroundDrawable(shape(surface,17));
    TextView title=text("MOVEMENT LOCKOUT",21);title.setTextColor(accent);
    title.setTypeface(null,1);panel.addView(title);
    TextView details=text(motionReason,16);details.setTextColor(Color.WHITE);
    motionDialogText=details;panel.addView(details);
    TextView footer=text("Video is paused while motion is detected. Playback can resume after GPS reports stationary.",14);
    panel.addView(footer);
    Button openSettings=btn("Settings",new Runnable(){public void run(){
      if(motionDialog!=null){motionDialog.dismiss();motionDialog=null;motionDialogText=null;}
      render("Settings",true);
    }});
    openSettings.setBackgroundDrawable(shape(accent,12));openSettings.setTextColor(0xff071312);
    panel.addView(openSettings,new LinearLayout.LayoutParams(-1,dp(52)));
    dialog.setContentView(panel);dialog.show();
    Window window=dialog.getWindow();
    if(window!=null){
      window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels-dp(26),dp(690)),
        WindowManager.LayoutParams.WRAP_CONTENT);
      window.setGravity(Gravity.CENTER);
      window.getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
  }

  void confirmExitStremio(){
    new AlertDialog.Builder(this)
      .setTitle("Exit Stremio?")
      .setMessage("Close Stremio completely and stop its background activity? Your login and settings will be saved.")
      .setNegativeButton("No",null)
      .setPositiveButton("Yes, Exit",new DialogInterface.OnClickListener(){
        public void onClick(DialogInterface dialog,int which){exitStremioCompletely();}
      }).show();
  }

  void exitStremioCompletely(){
    if(closingApp)return;
    closingApp=true;
    if(motionGuard!=null)motionGuard.stop();
    if(motionDialog!=null){motionDialog.dismiss();motionDialog=null;}
    if(parkedDialog!=null){parkedDialog.dismiss();parkedDialog=null;}
    refreshHandler.removeCallbacks(homeRefresh);
    apiWorkers.shutdownNow();catalogWorkers.shutdownNow();
    sportsWorkers.shutdownNow();imageWorkers.shutdownNow();
    // This shuts down the app process, not merely this screen. It does not
    // terminate a separate VLC process launched by the user.
    try{finishAffinity();}catch(Throwable ignored){finish();}
    android.os.Process.killProcess(android.os.Process.myPid());
  }

  String enc(String v)throws Exception{return java.net.URLEncoder.encode(v,"UTF-8").replace("+","%20");}
  void showCatalog(String title,String url,String type){
    final LinearLayout section=new LinearLayout(this);section.setOrientation(1);add(section);
    TextView heading=text(title,20);heading.setTypeface(null,1);section.addView(heading);
    section.addView(text("Loading…",13));requestCatalog(url,type,section);
  }
  // Keep artwork diagnostics independent from the account/API failure counter.
  void posterError(String description){
    synchronized(artworkCache){posterFailed++;lastPosterError=description;}
  }
  void hidePosterLabel(ImageView image){
    Object label=image.getTag();
    if(label instanceof View)((View)label).setVisibility(View.GONE);
  }
  // Memory + disk cache, shared downloads and bounded decoder threads.
  // No fresh download when navigating between Home, Search and Library.
  Bitmap scaledBitmap(byte[] raw){
    if(raw==null||raw.length==0)return null;
    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
    BitmapFactory.decodeByteArray(raw,0,raw.length,bounds);
    int w=bounds.outWidth,h=bounds.outHeight;
    if(w<=0||h<=0)return null;
    int sample=1;while(w/sample>360||h/sample>480)sample*=2;
    BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.RGB_565;
    return BitmapFactory.decodeByteArray(raw,0,raw.length,options);
  }
  byte[] remoteImage(String url)throws Exception{
    HttpURLConnection c=null;InputStream in=null;ByteArrayOutputStream out=new ByteArrayOutputStream();
    try{
      c=imageConnection(url);c.setRequestProperty("Accept","image/jpeg,image/png,image/webp");
      int status=c.getResponseCode();if(status>=400)throw new IOException("Poster HTTP "+status);
      in=responseStream(c,status);if(in==null)throw new IOException("No poster response body");
      byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){
        out.write(buffer,0,n);if(out.size()>3*1024*1024)throw new IOException("Poster exceeds 3 MB");
      }
      return out.toByteArray();
    }finally{try{if(in!=null)in.close();}catch(Exception ignored){}if(c!=null)c.disconnect();}
  }
  void poster(final ImageView view,final String url){poster(view,url,"");}
  void poster(final ImageView view,final String url,final String fallback){
    if(!url.startsWith("https://")&&!url.startsWith("http://")){posterError("Invalid poster URL");return;}
    Long recent=recentlyBadPosters.get(url);
    if(recent!=null&&System.currentTimeMillis()-recent<10*60*1000L){
      if(!fallback.isEmpty()&&!fallback.equals(url))poster(view,fallback,"");
      else view.setBackgroundColor(0xff353b55);
      return;
    }
    Bitmap hit=memoryPosters.get(url);
    if(hit!=null&&!hit.isRecycled()){view.setImageBitmap(hit);hidePosterLabel(view);return;}
    ArrayList<WeakReference<ImageView>> waiting=pendingImages.get(url);
    if(waiting!=null){waiting.add(new WeakReference<ImageView>(view));return;}
    waiting=new ArrayList<WeakReference<ImageView>>();waiting.add(new WeakReference<ImageView>(view));
    pendingImages.put(url,waiting);
    final String alt=fallback;
    imageWorkers.execute(new Runnable(){public void run(){
      Bitmap bitmap=null;String fail="";
      try{
        File cache=posterFile(url);byte[] raw=null;
        if(cache.isFile()){try{raw=bytes(cache,3*1024*1024);bitmap=scaledBitmap(raw);}catch(Exception ignored){cache.delete();}}
        if(bitmap==null){raw=remoteImage(url);bitmap=scaledBitmap(raw);if(bitmap==null)throw new IOException("Unsupported poster format");saveBytes(cache,raw);}
      }catch(Exception e){fail=e.getClass().getSimpleName()+": "+e.getMessage();}
      final Bitmap result=bitmap;final String failure=fail;
      runOnUiThread(new Runnable(){public void run(){
        ArrayList<WeakReference<ImageView>> views=pendingImages.remove(url);
        if(result!=null){
          memoryPosters.put(url,result);posterLoaded++;
        }else{recentlyBadPosters.put(url,System.currentTimeMillis());posterError("Poster "+safeEndpoint(url)+": "+failure);}
        if(views==null)return;
        for(WeakReference<ImageView> ref:views){
          ImageView v=ref.get();if(v==null||v.getParent()==null)continue;
          if(result!=null){v.setImageBitmap(result);hidePosterLabel(v);}
          else if(!alt.isEmpty()&&!alt.equals(url))poster(v,alt,"");
          else v.setBackgroundColor(0xff353b55);
        }
      }});
    }});
  }
  String artworkId(String id){
    if(id.startsWith("tt")){int colon=id.indexOf(':');return colon>0?id.substring(0,colon):id;}
    return id;
  }
  void resolvePoster(final ImageView cover,final String id,final String type){
    
    final String key=type+"/"+artworkId(id);
    if(!key.startsWith("movie/tt")&&!key.startsWith("series/tt")){
      synchronized(artworkCache){posterMissing++;}
      return;
    }
    String cached;
    synchronized(artworkCache){cached=artworkCache.get(key);}
    if(cached!=null){if(!cached.isEmpty())poster(cover,cached);return;}
    new Thread(new Runnable(){public void run(){
      try{
        JSONObject details=get(CINEMETA+"meta/"+type+"/"+enc(artworkId(id))+".json");
        JSONObject metadata=details.optJSONObject("meta");
        String resolved=metadata==null?"":metadata.optString("poster","");
        if(resolved.isEmpty()&&metadata!=null)resolved=metadata.optString("background","");
        final String finalUrl=resolved;
        synchronized(artworkCache){artworkCache.put(key,finalUrl);if(finalUrl.isEmpty())posterMissing++;}
        if(finalUrl.startsWith("https://")||finalUrl.startsWith("http://")){
          runOnUiThread(new Runnable(){public void run(){if(cover.getParent()!=null)poster(cover,finalUrl);}});
        }
      }catch(Exception e){posterError("Metadata "+key+": "+e.getClass().getSimpleName()+" "+e.getMessage());}
    }}).start();
  }
  void showTitle(final JSONObject meta,final String fallback,LinearLayout row){
    final String id=meta.optString("id",meta.optString("_id","")),mt=meta.optString("type",fallback),name=meta.optString("name",meta.optString("title",id));
    if(id.isEmpty())return;
    LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(5),dp(4),dp(5),dp(8));
    ImageView cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setBackgroundDrawable(shape(surface,10));
    FrameLayout tile=new FrameLayout(this);
    tile.addView(cover,new FrameLayout.LayoutParams(-1,-1));
    TextView label=text(name,15);label.setGravity(Gravity.CENTER);
    label.setTextColor(Color.WHITE);label.setMaxLines(4);
    FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-1);
    tile.addView(label,lp);cover.setTag(label);
    card.addView(tile,new LinearLayout.LayoutParams(dp(140),dp(185)));
    String posterUrl=meta.optString("poster","");
    if(posterUrl.isEmpty()){
      JSONObject nested=meta.optJSONObject("meta");
      if(nested!=null)posterUrl=nested.optString("poster","");
    }
    String fallbackPoster=artworkId(id).startsWith("tt")?"https://images.metahub.space/poster/medium/"+artworkId(id)+"/img":"";
    if(posterUrl.startsWith("https://")||posterUrl.startsWith("http://"))poster(cover,posterUrl,fallbackPoster);
    else if(!fallbackPoster.isEmpty())poster(cover,fallbackPoster);
    else {cover.setBackgroundColor(0xff353b55);synchronized(artworkCache){posterMissing++;}}
    final Runnable openTitle=new Runnable(){public void run(){selectedId=id;selectedType=mt;selectedName=name;rememberTitle();render("Streams",true);}};
    View.OnClickListener openPoster=new View.OnClickListener(){public void onClick(View tapped){openTitle.run();}};
    tile.setOnClickListener(openPoster);
    cover.setOnClickListener(openPoster);
    label.setOnClickListener(openPoster);
    card.setOnClickListener(openPoster);
    tile.setFocusable(true);
    tile.setContentDescription("Open "+name+" streams");
    Button button=btn(name,openTitle);
    button.setTextSize(13);button.setBackgroundDrawable(shape(surface,8));card.addView(button,new LinearLayout.LayoutParams(dp(140),dp(50)));
    row.addView(card,new LinearLayout.LayoutParams(dp(150),-2));
  }
  void displayCatalog(JSONObject j,String type,LinearLayout section){
    if(section.getParent()==null||!page.equals("Home"))return;
    JSONArray metas=j.optJSONArray("metas");
    section.removeViews(1,section.getChildCount()-1);
    if(metas==null||metas.length()==0){section.addView(text("No titles returned.",14));return;}
    LinearLayout row=new LinearLayout(this);row.setOrientation(0);
    HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.addView(row);
    // Prioritize fewer thumbnails to avoid hundreds of network requests at once.
    for(int i=0;i<metas.length()&&i<14;i++){JSONObject meta=metas.optJSONObject(i);if(meta!=null)showTitle(meta,type,row);}
    section.addView(scroll);
  }
  void notifyCatalog(String url,String type,JSONObject data){
    ArrayList<WeakReference<LinearLayout>> views=catalogWaiters.remove(url);
    if(views==null)return;
    for(WeakReference<LinearLayout> ref:views){
      LinearLayout row=ref.get();
      if(row!=null&&row.getParent()!=null&&page.equals("Home"))displayCatalog(data,type,row);
    }
  }
  void requestCatalog(final String url,final String type,final LinearLayout section){
    ArrayList<WeakReference<LinearLayout>> views=catalogWaiters.get(url);
    if(views==null){views=new ArrayList<WeakReference<LinearLayout>>();catalogWaiters.put(url,views);}
    views.add(new WeakReference<LinearLayout>(section));
    JSONObject cached=catalogMemory.get(url);
    if(cached!=null)displayCatalog(cached,type,section);
    Long timestamp=catalogFetched.get(url);
    if(cached!=null&&timestamp!=null&&System.currentTimeMillis()-timestamp<CATALOG_AGE){catalogWaiters.remove(url);return;}
    if(catalogPending.contains(url))return;
    catalogPending.add(url);
    ("sport".equalsIgnoreCase(type)?sportsWorkers:catalogWorkers).execute(new Runnable(){public void run(){
      JSONObject data=null;
      try{
        data=readJson(dataFile("catalogs",url));
        if(data!=null){
          final JSONObject old=data;
          runOnUiThread(new Runnable(){public void run(){
            catalogMemory.put(url,old);
            notifyCatalog(url,type,old);
          }});
          if(System.currentTimeMillis()-dataFile("catalogs",url).lastModified()<CATALOG_AGE){
            final JSONObject disk=data;
            runOnUiThread(new Runnable(){public void run(){catalogFetched.put(url,System.currentTimeMillis());catalogPending.remove(url);}});
            return;
          }
        }
        data=get(url);final JSONObject result=data;
        cacheJson(dataFile("catalogs",url),result);
        runOnUiThread(new Runnable(){public void run(){
          catalogMemory.put(url,result);catalogFetched.put(url,System.currentTimeMillis());catalogPending.remove(url);
          notifyCatalog(url,type,result);
        }});
      }catch(final Exception e){
        runOnUiThread(new Runnable(){public void run(){
          catalogPending.remove(url);
          ArrayList<WeakReference<LinearLayout>> waiting=catalogWaiters.remove(url);
          String reason=e.getClass().getSimpleName();
          String detail=e.getMessage();
          if(detail!=null&&detail.startsWith("HTTP "))reason=detail.split(" at ")[0];
          final String location=catalogHost(url);
          if(waiting!=null)for(WeakReference<LinearLayout> reference:waiting){
            final LinearLayout target=reference.get();
            if(target==null||target.getParent()==null||!page.equals("Home"))continue;
            if(catalogMemory.containsKey(url))continue;
            if(target.getChildCount()>1)target.removeViews(1,target.getChildCount()-1);
            target.addView(text("Catalog unavailable: "+location+" ("+reason+").",14));
            target.addView(btn("Retry this catalog",new Runnable(){public void run(){
              if(target.getChildCount()>1)target.removeViews(1,target.getChildCount()-1);
              target.addView(text("Loading…",13));requestCatalog(url,type,target);
            }}));
          }
          recordError(new IOException("Catalog "+location+" failed: "+reason+"; cause: "+sslReason(e)));
        }});
      }
    }});
  }
  String catalogHost(String url){
    try{return new URL(url).getHost();}catch(Exception ignored){return "addon server";}
  }
  String sslReason(Exception error){
    Throwable cause=error;
    int depth=0;
    while(cause.getCause()!=null&&cause.getCause()!=cause&&depth++<6)cause=cause.getCause();
    String result=cause.getClass().getSimpleName();
    String message=cause.getMessage();
    if(message!=null){
      String safe=message.replaceAll("https?://[^\\s]+","(remote server)");
      if(safe.length()>90)safe=safe.substring(0,90);
      result+=": "+safe;
    }
    return result;
  }
  String networkDetails(){StringBuilder b=new StringBuilder();
    try{java.util.Enumeration<java.net.NetworkInterface> all=java.net.NetworkInterface.getNetworkInterfaces();
      while(all!=null&&all.hasMoreElements()){java.net.NetworkInterface ni=all.nextElement();
        if(!ni.isUp()||ni.isLoopback())continue;
        b.append(ni.getName()).append(" ");
        java.util.Enumeration<java.net.InetAddress> addresses=ni.getInetAddresses();
        while(addresses.hasMoreElements()){java.net.InetAddress addr=addresses.nextElement();
          if(addr instanceof java.net.Inet4Address)b.append(addr.getHostAddress()).append(" ");}
        b.append("\n");
      }
    }catch(Exception e){b.append("Interface details unavailable: ").append(e.getClass().getSimpleName());}
    return b.toString();
  }
  void checkPublicIp(final TextView status){
    status.setText("Checking public internet connection…");
    new Thread(new Runnable(){public void run(){
      try{
        // Public HTTPS lookup. Do not send account credentials or playback URLs.
        JSONObject data=get("https://ipwho.is/");
        if(!data.optBoolean("success",false))throw new IOException("Public IP service returned an error");
        String ip=data.optString("ip","unknown");
        JSONObject conn=data.optJSONObject("connection");
        String isp=conn==null?"unknown":conn.optString("isp","unknown");
        String org=conn==null?"unknown":conn.optString("org","unknown");
        final String info="Public IP: "+ip+"\nProvider: "+isp+"\nOrganization: "+org+
          "\nThis identifies the app's internet exit, not VLC's independently selected route.";
        runOnUiThread(new Runnable(){public void run(){if(status.getParent()!=null)status.setText(info);}});
      }catch(final Exception ex){
        runOnUiThread(new Runnable(){public void run(){
          if(status.getParent()!=null)status.setText("Public IP lookup failed: "+ex.getClass().getSimpleName()+
            ". Check connectivity or try Refresh.");
        }});
      }
    }}).start();
  }

  // Playback transport audit. Kernel interface counters include all system traffic, not just VLC.
  // We additionally sample VLC UID traffic when the KitKat firmware exposes it.
  static class VideoSample {
    long at, mobile=-1, vlc=-1;
    String active="unknown", route="unknown";
    java.util.HashMap<String,Long> rx=new java.util.HashMap<String,Long>();
  }
  VideoSample videoBaseline;
  long counter(String path) {
    BufferedReader r=null;
    try {r=new BufferedReader(new FileReader(path));String x=r.readLine();return x==null?-1:Long.parseLong(x.trim());}
    catch(Exception ignored){return -1;}
    finally{if(r!=null)try{r.close();}catch(Exception ignored){}}
  }
  java.util.HashMap<String,Long> interfaceCounters() {
    java.util.HashMap<String,Long> m=new java.util.HashMap<String,Long>();
    BufferedReader r=null;
    try{
      r=new BufferedReader(new FileReader("/proc/net/dev"));String line;
      while((line=r.readLine())!=null){
        int colon=line.indexOf(':');if(colon<0)continue;
        String name=line.substring(0,colon).trim();
        String[] fields=line.substring(colon+1).trim().split("\\s+");
        if(fields.length>0)try{m.put(name,Long.parseLong(fields[0]));}catch(Exception ignored){}
      }
    }catch(Exception ignored){}
    finally{if(r!=null)try{r.close();}catch(Exception ignored){}}
    // Some Gen5W Android kernels hide proc network counters: try sysfs individually.
    try{
      File dir=new File("/sys/class/net");
      File[] names=dir.listFiles();
      if(names!=null)for(File file:names){
        String name=file.getName();
        long v=counter("/sys/class/net/"+name+"/statistics/rx_bytes");
        if(v>=0)m.put(name,v);
      }
    }catch(Exception ignored){}
    return m;
  }
  String defaultIpv4Route(){
    BufferedReader r=null;
    try{
      r=new BufferedReader(new FileReader("/proc/net/route"));String line;
      while((line=r.readLine())!=null){
        String[] parts=line.trim().split("\\s+");
        if(parts.length>=3&&"00000000".equals(parts[1]))return parts[0];
      }
    }catch(Exception ignored){}
    finally{if(r!=null)try{r.close();}catch(Exception ignored){}}
    return "unavailable";
  }
  VideoSample sampleVideoNetwork(){
    VideoSample v=new VideoSample();v.at=System.currentTimeMillis();
    try{v.mobile=android.net.TrafficStats.getMobileRxBytes();}catch(Exception ignored){}
    try{
      int uid=getPackageManager().getApplicationInfo("org.videolan.vlc",0).uid;
      v.vlc=android.net.TrafficStats.getUidRxBytes(uid);
    }catch(Exception ignored){}
    v.rx=interfaceCounters();
    v.route=defaultIpv4Route();
    try{
      ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
      NetworkInfo n=cm==null?null:cm.getActiveNetworkInfo();
      v.active=n==null?"none":n.getTypeName()+"/"+n.getState();
    }catch(Exception ignored){}
    return v;
  }
  long bytesDifference(long before,long after){return before>=0&&after>=before?after-before:-1;}
  String mb(long b){return b<0?"unavailable":String.format(java.util.Locale.US,"%.2f MB",(double)b/1048576.0);}
  long categoryRx(VideoSample before,VideoSample after,boolean cellular){
    long sum=0;boolean found=false;
    for(String name:before.rx.keySet()){
      String n=name.toLowerCase(java.util.Locale.US);
      boolean match=cellular?(n.startsWith("rmnet")||n.startsWith("ccmni")||n.startsWith("wwan")||n.startsWith("pdp")||n.startsWith("v4-rmnet")):(n.startsWith("wlan")||n.startsWith("wifi"));
      if(!match||!after.rx.containsKey(name))continue;
      long d=bytesDifference(before.rx.get(name),after.rx.get(name));
      if(d>=0){sum+=d;found=true;}
    }
    return found?sum:-1;
  }
  String videoReport(VideoSample before,VideoSample after){
    long vlc=bytesDifference(before.vlc,after.vlc);
    long car=categoryRx(before,after,true);
    long wifi=categoryRx(before,after,false);
    long mobile=bytesDifference(before.mobile,after.mobile);
    long seconds=Math.max(0,(after.at-before.at)/1000L);
    StringBuilder b=new StringBuilder();
    b.append("Playback session: ").append(seconds).append(" seconds");
    b.append("\nVLC app received: ").append(mb(vlc));
    b.append("\nCar modem interfaces (rmnet etc.): ").append(mb(car));
    b.append("\nWi-Fi interface received: ").append(mb(wifi));
    b.append("\nAndroid mobile-data counter: ").append(mb(mobile));
    b.append("\nAndroid network: ").append(before.active).append(" -> ").append(after.active);
    b.append("\nDefault IPv4 route: ").append(before.route).append(" -> ").append(after.route);
    if(car>=2097152 || mobile>=2097152){
      b.append("\nRESULT: VEHICLE CELLULAR TRAFFIC DETECTED. It was active during VLC playback. Other car services may contribute, so this is not an exact VLC-to-modem attribution.");
    }else if(vlc>=2097152 && car>=0 && car<131072 && mobile>=0 && mobile<131072 && wifi>=2097152){
      b.append("\nRESULT: VIDEO DATA APPEARS TO USE WI-FI, NOT THE CAR MODEM. Still not proof of iPhone cellular: use the phone-data-off comparison below.");
    }else{
      b.append("\nRESULT: INCONCLUSIVE. Counters may be unavailable, video may have been buffered, or other head-unit traffic may be mixed in.");
    }
    b.append("\nTo verify: repeat with iPhone Cellular Data OFF (keep Wi-Fi and Bluetooth ON). Open a NEW video. If it still downloads, phone cellular is not required; if it stops, phone dependence is suggested but not proven.");
    return b.toString();
  }
  void startVideoMeasurement(){videoBaseline=sampleVideoNetwork();}
  void finishVideoMeasurement(){
    if(videoBaseline==null)return;
    VideoSample after=sampleVideoNetwork();
    String result=videoReport(videoBaseline,after);
    videoBaseline=null;
    prefs.edit().putString("video_route_report",result).apply();
  }

  void testArtworkServer(final TextView status){
    status.setText("Testing a sample poster from the artwork CDN…");
    imageWorkers.execute(new Runnable(){public void run(){
      String testUrl="https://images.metahub.space/poster/medium/tt0133093/img";
      String result;
      HttpURLConnection c=null;InputStream in=null;
      try{
        c=imageConnection(testUrl);c.setRequestProperty("Accept","image/jpeg,image/png,image/webp");
        int code=c.getResponseCode();
        String type=c.getContentType();
        if(code>=400)throw new IOException("Poster server HTTP "+code);
        in=responseStream(c,code);
        if(in==null)throw new IOException("No image response body");
        byte[] buf=new byte[8192];ByteArrayOutputStream out=new ByteArrayOutputStream();
        int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);if(out.size()>1024*1024)break;}
        Bitmap bmp=scaledBitmap(out.toByteArray());
        result="Modern trusted CA roots: "+trustedPosterRoots+" • Artwork server HTTP "+code+" • Type: "+type+" • Received "+out.size()+" bytes • Decoder: "+(bmp==null?"FAILED":"OK");
        if(bmp!=null)bmp.recycle();
      }catch(Exception e){result="Artwork server FAILED: "+e.getClass().getSimpleName()+" • "+e.getMessage();}
      finally{try{if(in!=null)in.close();}catch(Exception ignored){}if(c!=null)c.disconnect();}
      final String report=result;
      runOnUiThread(new Runnable(){public void run(){if(status.getParent()!=null)status.setText(report);}});
    }});
  }

  void auditAccount(final TextView output){
    if(auth.isEmpty()){output.setText("Not signed in. Sign in under Account first.");return;}
    output.setText("Checking your Stremio account and the server's library…");
    final String key=auth;
    apiWorkers.execute(new Runnable(){public void run(){
      try{
        JSONObject payload=new JSONObject();payload.put("authKey",key);
        JSONObject user=post("getUser",payload);
        JSONObject profile=user.optJSONObject("result");
        JSONObject u=profile==null?null:profile.optJSONObject("user");
        if(u==null)u=profile;
        final String email=u==null?"not returned":u.optString("email","not returned");
        final String identifier=u==null?"not returned":u.optString("_id",u.optString("id","not returned"));
        JSONObject req=new JSONObject();req.put("authKey",key);
        req.put("collection","libraryItem");req.put("ids",new JSONArray());req.put("all",true);
        JSONObject answer=post("datastoreGet",req);
        final JSONArray items=extractLibrary(answer);
        if(items==null)throw new IOException("Datastore result has no recognized library array");
        int progress=0;StringBuilder examples=new StringBuilder();
        for(int i=0;i<items.length();i++){
          JSONObject item=items.optJSONObject(i);
          if(isInProgress(item))progress++;
          if(item!=null&&examples.length()<180&&i<5){
            if(examples.length()>0)examples.append(", ");
            examples.append(item.optString("name",item.optString("_id","untitled")));
          }
        }
        final String report="Stremio account authenticated"+
          "\nItems returned by Stremio server: "+items.length()+
          "\nTitles with watch progress: "+progress+
          "\nPreviously shown locally: "+libraryItems.length()+" from "+lastLibrarySource+
          "\nIf the library differs from your other devices, verify you are signed into the same Stremio account.";
        runOnUiThread(new Runnable(){public void run(){
          if(!key.equals(auth))return;
          libraryItems=items;lastLibrarySync=System.currentTimeMillis();
          lastLibrarySource="Stremio server audit • "+items.length()+" items";
          if(email.contains("@")){verifiedAccountEmail=email;prefs.edit().putString("verified_account_email",email).apply();}
          lastAccountAudit=report;saveLibraryCache();
          if(output.getParent()!=null)output.setText(report);
        }});
      }catch(final Exception e){
        final String fail="Account audit failed: "+e.getClass().getSimpleName()+": "+e.getMessage();
        runOnUiThread(new Runnable(){public void run(){lastAccountAudit=fail;if(output.getParent()!=null)output.setText(fail);}});
      }
    }});
  }

  void diagnostics(){
    add(text("INSTALLED APK: "+installedBuild()+" (Android versionCode "+installedVersionCode()+")",17));
    add(text("Sports Streams HTTPS: "+VideoTls.sportsTransportStatus(),14));
    add(text("NETWORK AND ERROR DIAGNOSTICS",22));
    add(text("STREMIO ACCOUNT AND LIBRARY AUDIT",21));
    final TextView accountAudit=text(lastAccountAudit,15);add(accountAudit);
    add(btn("Audit Stremio account and server library",new Runnable(){public void run(){auditAccount(accountAudit);}}));
    add(text("Local library: "+libraryItems.length()+" items • "+lastLibrarySource,15));
    add(text("Stremio account: "+(auth.isEmpty()?"not signed in":"signed in")+" • Library entries: "+libraryItems.length(),16));
    add(text("VIDEO DATA TEST",22));
    add(text("No special setup: start a fresh video, watch at least 60 seconds, close VLC and return here. The app automatically measures the modem, Wi-Fi, and VLC received data while playback is open. These are diagnostic indicators, not definitive per-packet proof.",16));
    add(text(prefs.getString("video_route_report","No VLC playback measured with this build yet."),16));
    add(btn("Clear video data test result",new Runnable(){public void run(){prefs.edit().remove("video_route_report").apply();render("Diagnostics",false);}}));
    add(text("Use Internal Modem preference: "+(getSharedPreferences("network",0).getBoolean("use_internal_modem",false)?"ON — allow normal Android route":"OFF — request Wi-Fi/hotspot-only firewall"),16));
    add(text("NETWORK GUARD: NEMESIS service stremiogen5w/netguard applies this preference within a few seconds. Verify its log and firewall rules. Android system DNS and VLC are outside the app UID rule.",16));
    ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
    try {
      NetworkInfo active=cm==null?null:cm.getActiveNetworkInfo();
      add(text("Android active network: "+(active==null?"none":active.getTypeName()+" / "+active.getState()),17));
    }catch(Exception e){add(text("Network state unavailable: "+e.getClass().getSimpleName(),16));}
    add(text("Active interfaces (diagnostic only):\n"+networkDetails(),15));
    add(text("PUBLIC IP AND INTERNET PROVIDER",20));
    final TextView publicIpStatus=text("Checking public internet connection…",16);
    add(publicIpStatus);
    checkPublicIp(publicIpStatus);
    add(btn("Refresh public IP and provider",new Runnable(){public void run(){checkPublicIp(publicIpStatus);}}));
    add(text("Phone-only route: NOT VERIFIED. Wireless CarPlay alone cannot prove upstream internet source.",17));
    add(text("Failed requests: "+errors.size(),18));
    add(text("ARTWORK TROUBLESHOOTING",19));
    final TextView artworkTest=text("Tap below to test a real poster from the image provider.",15);add(artworkTest);
    add(btn("Test artwork server",new Runnable(){public void run(){testArtworkServer(artworkTest);}}));
    synchronized(artworkCache){
      add(text("Artwork: "+posterLoaded+" loaded / "+posterFailed+" failed / "+posterMissing+" missing metadata",16));
      if(!lastPosterError.isEmpty())add(text("Last artwork error: "+lastPosterError,14));
    }
    add(btn("Test Stremio API and Cinemeta",new Runnable(){public void run(){request(new Job(){public JSONObject run()throws Exception{return get(CINEMETA+"manifest.json");}},new Result(){public void done(JSONObject j){add(text("Cinemeta HTTPS: OK ("+j.optString("name","manifest")+")",16));}});if(!auth.isEmpty())request(new Job(){public JSONObject run()throws Exception{return post("addonCollectionGet",authJson());}},new Result(){public void done(JSONObject j){add(text("Stremio account API: OK",16));}});}}));
    for(String error:errors)add(text(error,14));
    add(btn("Retry account add-ons",new Runnable(){public void run(){refreshAddons();}}));
    add(btn("Retry library",new Runnable(){public void run(){render("Library",true);}}));
  }
  void account(){if(!auth.isEmpty()){add(text("Signed in to Stremio",20));add(text("Your library and Continue Watching sync with your signed-in Stremio account.",15));add(btn("Verify signed-in Stremio account",new Runnable(){public void run(){refreshAccountIdentity();}}));add(btn("Sync my library and Continue Watching now",new Runnable(){public void run(){refreshLibrary(true);message("Refreshing your Stremio account library.");}}));add(btn("Refresh add-ons",new Runnable(){public void run(){refreshAddons();}}));add(btn("Sign out",new Runnable(){public void run(){auth="";verifiedAccountEmail="";addons=new JSONArray();libraryItems=new JSONArray();lastAddonSync=0;lastLibrarySync=0;catalogMemory.clear();prefs.edit().remove("auth").remove("verified_account_email").apply();render("Account",false);}}));return;}
    add(text("Sign in using your Stremio account. Credentials are sent to api.strem.io over HTTPS and are not saved on the device.",16));final EditText email=new EditText(this),pass=new EditText(this);styleField(email);email.setHint("Email");email.setText("");styleField(pass);pass.setHint("Password");pass.setInputType(129);add(email);add(pass);add(btn("Sign in",new Runnable(){public void run(){final String e=email.getText().toString(),p=pass.getText().toString();if(e.isEmpty()||p.isEmpty()){message("Enter email and password");return;}request(new Job(){public JSONObject run()throws Exception{JSONObject q=new JSONObject();q.put("email",e);q.put("password",p);return post("login",q);}},new Result(){public void done(JSONObject j){JSONObject result=j.optJSONObject("result");String token=result==null?"":result.optString("authKey","");if(token.isEmpty()){message("Login response did not include an auth key. Check API compatibility.");return;}auth=token;addons=new JSONArray();libraryItems=new JSONArray();verifiedAccountEmail="";lastAddonSync=0;lastLibrarySync=0;prefs.edit().putString("auth",token).remove("last_email").remove("verified_account_email").apply();restoreAccountCache();refreshAccountIdentity();refreshAddons(true);refreshLibrary(true);render("Account",false);}});}}));
  }
  JSONObject authJson()throws Exception{JSONObject j=new JSONObject();j.put("authKey",auth);return j;}
  final Handler refreshHandler=new Handler();
  final Runnable homeRefresh=new Runnable(){public void run(){if(page.equals("Home")&&!isFinishing())render("Home",false);}};
  void scheduleHomeRefresh(){
    if(!page.equals("Home"))return;
    refreshHandler.removeCallbacks(homeRefresh);refreshHandler.postDelayed(homeRefresh,800);
  }
  void refreshAddons(){refreshAddons(false);}
  void refreshAddons(boolean force){
    if(auth.isEmpty()||addonsLoading)return;
    if(!force&&lastAddonSync>0&&System.currentTimeMillis()-lastAddonSync<ADDONS_AGE)return;
    addonsLoading=true;
    request(new Job(){public JSONObject run()throws Exception{return post("addonCollectionGet",authJson());}},new Result(){public void done(JSONObject j){
      addonsLoading=false;
      JSONArray a=j.optJSONArray("result");if(a==null){JSONObject r=j.optJSONObject("result");if(r!=null)a=r.optJSONArray("addons");}
      if(a==null){recordError(new IOException("Add-on response format unknown"));return;}
      // Copy working manifests from the previous list by transport URL.
      HashMap<String,JSONObject> existing=new HashMap<String,JSONObject>();
      for(int i=0;i<addons.length();i++){JSONObject old=addons.optJSONObject(i);if(old!=null&&old.optJSONObject("manifest")!=null)existing.put(manifestUrl(old),old.optJSONObject("manifest"));}
      addons=a;lastAddonSync=System.currentTimeMillis();
      for(int i=0;i<addons.length();i++){
        final JSONObject entry=addons.optJSONObject(i);
        if(entry==null)continue;
        final String manifest=manifestUrl(entry);
        if(entry.optJSONObject("manifest")==null&&existing.containsKey(manifest)){
          try{entry.put("manifest",existing.get(manifest));}catch(Exception ignored){}
        }
        if(entry.optJSONObject("manifest")!=null)continue;
        if(!manifest.startsWith("https://"))continue;
        final String endpoint=manifest.endsWith("manifest.json")?manifest:baseUrl(manifest)+"manifest.json";
        request(new Job(){public JSONObject run()throws Exception{return get(endpoint);}},new Result(){public void done(JSONObject md){
          try{entry.put("manifest",md);saveAddonCache();if(page.equals("Add-ons"))render("Add-ons",false);else scheduleHomeRefresh();}
          catch(Exception e){recordError(e);}
        }});
      }
      saveAddonCache();
      if(page.equals("Add-ons"))render(page,false);else scheduleHomeRefresh();
    }});
  }
  String manifestUrl(JSONObject a){
    String url=a.optString("transportUrl",a.optString("url",""));
    if(url.isEmpty())url=a.optString("manifestUrl","");
    // Sports Streams moved its known hosts in September 2026. Keep all configuration paths intact.
    try{
      URL parsed=new URL(url);
      String host=parsed.getHost().toLowerCase(Locale.US);
      if(host.equals("sports.highfly.dev")||host.equals("sportsfree.highfly.dev")||
         host.equals("sportsfree-us2.highfly.dev")||host.equals("premium.highfly.dev"))
        url=url.replace("://"+parsed.getHost(),"://"+host.substring(0,host.length()-4)+".to");
    }catch(Exception ignored){}
    return url;
  }
  String baseUrl(String manifest){if(manifest.endsWith("/manifest.json"))return manifest.substring(0,manifest.length()-"manifest.json".length());if(manifest.endsWith("manifest.json"))return manifest.substring(0,manifest.lastIndexOf('/')+1);return manifest.endsWith("/")?manifest:manifest+"/";}
  void showAddons(){if(auth.isEmpty()){add(text("Sign in to synchronize add-ons.",18));return;}add(text("Synced add-ons: "+addons.length(),18));for(int i=0;i<addons.length();i++){JSONObject a=addons.optJSONObject(i);if(a==null)continue;JSONObject m=a.optJSONObject("manifest");add(text((m==null?"Add-on":m.optString("name","Add-on"))+"\n"+manifestUrl(a).replaceAll("(?i)([?&](?:apiKey|token|key)=)[^&]+","$1[hidden]"),14));}add(btn("Refresh",new Runnable(){public void run(){refreshAddons();}}));}
  long progressMs(JSONObject item){
    JSONObject state=item.optJSONObject("state");
    return state==null?0:Math.max(0,state.optLong("timeOffset",0));
  }
  JSONObject fetchLibrary()throws Exception{
    JSONObject q=authJson();q.put("collection","libraryItem");q.put("ids",new JSONArray());q.put("all",true);
    return post("datastoreGet",q);
  }
  JSONArray extractLibrary(JSONObject response){
    Object r=response.opt("result");
    if(r instanceof JSONArray)return (JSONArray)r;
    if(r instanceof JSONObject){
      JSONObject o=(JSONObject)r;JSONArray a=o.optJSONArray("items");
      if(a==null)a=o.optJSONArray("data");
      if(a==null)a=o.optJSONArray("result");
      return a;
    }
    return null;
  }
  boolean isInProgress(JSONObject item){
    if(item==null||"other".equals(item.optString("type",""))||(item.optBoolean("removed",false)&&!item.optBoolean("temp",false)))return false;
    JSONObject state=item.optJSONObject("state");
    if(state==null)return false;
    long time=Math.max(0,state.optLong("timeOffset",0)),duration=state.optLong("duration",0);
    return time>0; // Mirror Stremio Core, including titles past the 95% mark.
  }
  void refreshLibrary(boolean force){
    if(auth.isEmpty()||libraryLoading)return;
    if(!force&&lastLibrarySync>0&&System.currentTimeMillis()-lastLibrarySync<LIBRARY_AGE)return;
    libraryLoading=true;
    request(new Job(){public JSONObject run()throws Exception{return fetchLibrary();}},new Result(){public void done(JSONObject reply){
      libraryLoading=false;
      JSONArray items=extractLibrary(reply);
      if(items==null){recordError(new IOException("Stremio datastoreGet returned no library items"));return;}
      libraryItems=items;
      lastLibrarySource="Stremio server • "+items.length()+" items";
      lastLibrarySync=System.currentTimeMillis();
      final JSONObject disk=new JSONObject();
      try{disk.put("items",items);}catch(Exception ignored){}
      apiWorkers.execute(new Runnable(){public void run(){cacheJson(libraryFile(),disk);}});
      if(page.equals("Library"))render("Library",false);
      else if(page.equals("Home"))render("Home",false);
    }});
  }
  void showContinueWatching(){
    if(auth.isEmpty())return;
    final LinearLayout section=new LinearLayout(this);section.setOrientation(1);add(section);
    section.addView(text("Continue watching",19));
    section.addView(text("Source: "+lastLibrarySource,13));
    java.util.ArrayList<JSONObject> watching=new java.util.ArrayList<JSONObject>();
    for(int i=0;i<libraryItems.length();i++){
      JSONObject item=libraryItems.optJSONObject(i);if(isInProgress(item))watching.add(item);
    }
    java.util.Collections.sort(watching,new java.util.Comparator<JSONObject>(){
      public int compare(JSONObject a,JSONObject b){
        JSONObject as=a.optJSONObject("state"),bs=b.optJSONObject("state");
        String at=as==null?"":as.optString("lastWatched","");
        String bt=bs==null?"":bs.optString("lastWatched","");
        return bt.compareTo(at); // Stremio returns UTC ISO-8601 strings, not epoch longs.
      }
    });
    if(watching.isEmpty()){
      section.addView(text(libraryLoading?"Syncing progress from Stremio…":"No in-progress titles in your synced account library.",15));
      return;
    }
    HorizontalScrollView scroll=new HorizontalScrollView(this);
    LinearLayout row=new LinearLayout(this);row.setOrientation(0);scroll.addView(row);
    for(int i=0;i<watching.size()&&i<15;i++){
      JSONObject it=watching.get(i);
      String id=it.optString("_id",it.optString("id",""));
      if(id.isEmpty())continue;
      JSONObject meta=new JSONObject();
      try{
        meta.put("id",id);meta.put("type",it.optString("type","movie"));
        meta.put("name",it.optString("name",id)+" • "+(progressMs(it)/60000)+" min");
        meta.put("poster",it.optString("poster",""));
        showTitle(meta,it.optString("type","movie"),row);
      }catch(Exception ignored){}
    }
    section.addView(scroll);
  }
  void loadLibrary(){
    if(auth.isEmpty()){add(text("Sign in to load your Stremio account library.",18));return;}
    add(text("STREMIO AUTO • Library",24));
    add(text("Account: "+(verifiedAccountEmail.isEmpty()?"verification pending":verifiedAccountEmail),15));
    add(text("Library source: "+lastLibrarySource,14));
    add(btn("Refresh library from Stremio",new Runnable(){public void run(){refreshLibrary(true);}}));
    if(libraryItems.length()==0){
      add(text(libraryLoading?"Syncing your account…":"No synced library items yet. Try Refresh, or verify your Stremio sign-in.",17));
      refreshLibrary(false);return;
    }
    JSONArray items=libraryItems;
    add(text(items.length()+" account library entries",18));
    int inProgress=0;for(int i=0;i<items.length();i++){if(isInProgress(items.optJSONObject(i)))inProgress++;}
    add(text("Continue Watching: "+inProgress+" titles",17));
    LinearLayout list=new LinearLayout(this);list.setOrientation(1);add(list);
    for(int i=0;i<items.length()&&i<100;i++){
      JSONObject it=items.optJSONObject(i);
      if(it==null||it.optBoolean("removed",false))continue;
      String id=it.optString("_id",it.optString("id","")),type=it.optString("type","movie");
      if(id.isEmpty())continue;
      String name=it.optString("name",it.optString("title",id));
      String progress=progressMs(it)>0?" • "+(progressMs(it)/60000)+" min":"";
      JSONObject display=new JSONObject();
      try{display.put("id",id);display.put("type",type);display.put("name",name+progress);display.put("poster",it.optString("poster",""));}catch(Exception ignored){}
      LinearLayout row=new LinearLayout(this);row.setOrientation(0);showTitle(display,type,row);list.addView(row);
    }
    refreshLibrary(false); // Fresh cache appears immediately; stale account data refreshes separately.
  }
  void discover(){
    add(text("Search movies and series",21));
    final EditText query=new EditText(this);styleField(query);query.setHint("Movie or series title");add(query);
    add(btn("Search",new Runnable(){public void run(){String q=query.getText().toString().trim();if(q.isEmpty())return;searchCatalogs(q);}}));
  }
  void searchCatalogs(String query){
    final String q=query;add(text("Results for: "+q,18));int total=0;
    for(final String t:new String[]{"movie","series"}){
      try{final String url=CINEMETA+"catalog/"+t+"/top/search="+enc(q)+".json";
        request(new Job(){public JSONObject run()throws Exception{return get(url);}},new Result(){public void done(JSONObject j){
          if(!page.equals("Search"))return;JSONArray arr=j.optJSONArray("metas");if(arr==null)return;
          add(text("Search results • "+t,19));LinearLayout row=new LinearLayout(MainActivity.this);row.setOrientation(0);
          HorizontalScrollView sc=new HorizontalScrollView(MainActivity.this);sc.addView(row);
          for(int z=0;z<arr.length()&&z<25;z++){JSONObject m=arr.optJSONObject(z);if(m!=null)showTitle(m,t,row);}add(sc);
        }});total++;
      }catch(Exception e){recordError(e);}
    }
    for(int i=0;i<addons.length();i++){
      JSONObject a=addons.optJSONObject(i);if(a==null)continue;JSONObject m=a.optJSONObject("manifest");if(m==null)continue;
      String base=baseUrl(manifestUrl(a));if(!base.startsWith("https://"))continue;
      JSONArray catalogs=m.optJSONArray("catalogs");if(catalogs==null)continue;
      for(int k=0;k<catalogs.length();k++){
        JSONObject c=catalogs.optJSONObject(k);if(c==null)continue;
        JSONArray extras=c.optJSONArray("extra");boolean search=false;
        if(extras!=null)for(int x=0;x<extras.length();x++){JSONObject ex=extras.optJSONObject(x);if(ex!=null&&"search".equals(ex.optString("name")))search=true;}
        if(!search)continue;
        final String type=c.optString("type","movie");
        try{
          final String url=base+"catalog/"+enc(type)+"/"+enc(c.optString("id"))+"/search="+enc(q)+".json";
          request(new Job(){public JSONObject run()throws Exception{return get(url);}},new Result(){public void done(JSONObject result){
            if(!page.equals("Search"))return;JSONArray metas=result.optJSONArray("metas");if(metas==null)return;
            for(int j=0;j<metas.length()&&j<30;j++){JSONObject meta=metas.optJSONObject(j);if(meta==null)continue;
              final String id=meta.optString("id",""),t=meta.optString("type",type);if(id.isEmpty())continue;
              LinearLayout resultRow=new LinearLayout(MainActivity.this);resultRow.setOrientation(0);
              showTitle(meta,t,resultRow);add(resultRow);
            }
          }});total++;
        }catch(Exception e){recordError(e);}
      }
    }
    if(total==0)add(text("No installed add-on exposes a searchable catalog. Check Add-ons.",16));
  }
  boolean supports(JSONObject m,String resource,String type,String id){
    if(m==null)return false;JSONArray resources=m.optJSONArray("resources");if(resources==null)return false;
    for(int i=0;i<resources.length();i++){
      Object o=resources.opt(i);JSONObject r=o instanceof JSONObject?(JSONObject)o:null;
      String name=r==null?String.valueOf(o):r.optString("name");if(!resource.equals(name))continue;
      JSONArray types=r==null?m.optJSONArray("types"):r.optJSONArray("types");if(types!=null){boolean ok=false;for(int t=0;t<types.length();t++)if(type.equals(types.optString(t)))ok=true;if(!ok)continue;}
      JSONArray prefixes=r==null?m.optJSONArray("idPrefixes"):r.optJSONArray("idPrefixes");if(prefixes!=null&&prefixes.length()>0){boolean ok=false;for(int t=0;t<prefixes.length();t++)if(id.startsWith(prefixes.optString(t)))ok=true;if(!ok)continue;}
      return true;
    }return false;
  }
  // Player can return the actual timestamp on Android's ACTION_RESULT contract.
  // Never guess position from wall-clock time: pausing and seeking would corrupt sync.
  JSONObject existingLibraryItem(String id){
    for(int i=0;i<libraryItems.length();i++){
      JSONObject it=libraryItems.optJSONObject(i);
      if(it!=null&&id.equals(it.optString("_id",it.optString("id",""))))return it;
    }
    return null;
  }
  void saveProgress(final long time,final long duration,final boolean automatic){
    if("series".equals(selectedType)&&!selectedId.matches(".*:[0-9]+:[0-9]+")){if(!automatic)message("Episode-specific information is required to sync series progress. No changes were sent to your Stremio account.");return;}
    if(auth.isEmpty()){if(!automatic)message("Sign into your Stremio account before saving progress.");return;}
    if(verifiedAccountEmail.isEmpty()){if(!automatic)message("Verify the Stremio account under Account before changing watch progress.");return;}
    if(existingLibraryItem(selectedId)==null){if(!automatic)message("This title was not present in your synced Stremio library. No new title was added automatically.");return;}
    if(selectedId.isEmpty()||time<0){message("No valid playback position to sync.");return;}
    final String mediaId=selectedId,mediaType=selectedType,mediaName=selectedName;
    request(new Job(){public JSONObject run()throws Exception{
      JSONObject old=existingLibraryItem(mediaId);
      JSONObject item=old==null?new JSONObject():new JSONObject(old.toString());
      String now=dateNow();
      item.put("_id",mediaId);item.put("name",mediaName);item.put("type",mediaType);
      item.put("removed",false);
      if(old==null){
        item.put("temp",true);item.put("_ctime",now);item.put("poster",mediaId.startsWith("tt")?"https://images.metahub.space/poster/medium/"+artworkId(mediaId)+"/img":"");
      }
      item.put("_mtime",now);
      JSONObject st=item.optJSONObject("state");if(st==null)st=new JSONObject();
      st.put("timeOffset",time);
      if(duration>0)st.put("duration",duration);
      else if(!st.has("duration"))st.put("duration",0);
      if(!st.has("timeWatched"))st.put("timeWatched",0);
      if(!st.has("overallTimeWatched"))st.put("overallTimeWatched",0);
      if(!st.has("timesWatched"))st.put("timesWatched",0);
      if(!st.has("flaggedWatched"))st.put("flaggedWatched",0);
      if(!st.has("watched"))st.put("watched","");
      if(!st.has("season"))st.put("season",0);
      if(!st.has("episode"))st.put("episode",0);
      if(!st.has("noNotif"))st.put("noNotif",false);
      st.put("lastWatched",now);
      if(!st.has("video_id"))st.put("video_id",mediaId);
      item.put("state",st);
      JSONObject payload=authJson();payload.put("collection","libraryItem");
      JSONArray updates=new JSONArray();updates.put(item);payload.put("changes",updates);
      return post("datastorePut",payload);
    }},new Result(){public void done(JSONObject response){
      lastLibrarySync=0;
      refreshLibrary(true);
      if(!automatic)message("Progress saved to your Stremio account. Other devices should see it after their next sync.");
    }});
  }
  void showManualProgress(){
    if(auth.isEmpty())return;
    add(text("SYNC WATCH PROGRESS",18));
    add(text(vlcIntegrationEnabled()?
      "After VLC closes, playback progress syncs automatically if VLC reports its actual position. Otherwise enter the minute reached and tap Save.":
      "If playback progress did not sync, enter the minute reached and tap Save.",14));
    final EditText minutes=new EditText(this);styleField(minutes);
    minutes.setHint("Minutes watched, for example 37");
    minutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    add(minutes);
    add(btn("Save progress to my Stremio account",new Runnable(){public void run(){
      try{long minute=Long.parseLong(minutes.getText().toString().trim());
        if(minute<0||minute>6000){message("Enter a valid minute.");return;}
        JSONObject existing=existingLibraryItem(selectedId);
        JSONObject state=existing==null?null:existing.optJSONObject("state");
        long duration=state==null?0:state.optLong("duration",0);
        saveProgress(minute*60000L,duration,false);
      }catch(Exception e){message("Please enter a number of minutes.");}
    }}));
  }
  // Source cards: show actual metadata returned by each add-on, not giant buttons
  // with a clipped, multi-line source title.
  String streamCombined(JSONObject stream){
    return stream.optString("name","")+" "+stream.optString("title","")+" "+stream.optString("description","")+" "+
      stream.optString("filename","")+" "+(stream.optJSONObject("behaviorHints")==null?"":stream.optJSONObject("behaviorHints").optString("filename",""));
  }
  String streamQuality(JSONObject stream){
    String s=streamCombined(stream).toLowerCase(java.util.Locale.US);
    if(s.contains("2160p")||s.contains("4k")||s.contains("uhd"))return "4K";
    if(s.contains("1080p")||s.contains("fhd"))return "1080p";
    if(s.contains("720p")||s.contains("hd")&&!s.contains("hdr"))return "720p";
    if(s.contains("480p"))return "480p";
    if(s.contains("360p"))return "360p";
    return "Unknown";
  }
  int streamPriority(JSONObject stream){
    String q=streamQuality(stream);
    if("720p".equals(q))return 0;
    if("480p".equals(q))return 1;
    if("360p".equals(q))return 2;
    if("1080p".equals(q))return 3;
    if("4K".equals(q))return 5;
    return 4;
  }
  String streamSize(JSONObject stream){
    JSONObject h=stream.optJSONObject("behaviorHints");
    long bytes=stream.optLong("fileSize",0);
    if(bytes<=0&&h!=null)bytes=h.optLong("videoSize",0);
    if(bytes>0){
      double gb=(double)bytes/(1024d*1024d*1024d);
      return gb>=1?String.format(java.util.Locale.US,"%.2f GB",gb):
        String.format(java.util.Locale.US,"%.0f MB",bytes/(1024d*1024d));
    }
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)([0-9]+(?:[.,][0-9]+)?)\\s*(GB|MB|GiB|MiB|TB)\\b").matcher(streamCombined(stream));
    if(m.find())return m.group(1).replace(',','.')+" "+m.group(2).toUpperCase(java.util.Locale.US);
    return "";
  }
  String sourceTags(JSONObject stream){
    String txt=streamCombined(stream).toLowerCase(java.util.Locale.US);
    StringBuilder info=new StringBuilder(streamQuality(stream));
    String size=streamSize(stream);if(!size.isEmpty())info.append("  •  ").append(size);
    if(txt.contains("h.264")||txt.contains("x264")||txt.contains("avc"))info.append("  •  H.264");
    else if(txt.contains("hevc")||txt.contains("x265")||txt.contains("h.265"))info.append("  •  HEVC");
    else if(txt.contains("av1"))info.append("  •  AV1");
    if(txt.contains("hdr"))info.append("  •  HDR");
    if(txt.contains("dolby vision")||txt.contains("dovi"))info.append("  •  DV");
    if(txt.contains("5.1"))info.append("  •  5.1 audio");
    return info.toString();
  }
  String firstInfoLine(JSONObject stream){
    String d=stream.optString("title",stream.optString("description",""));
    if(d.isEmpty())d=stream.optString("name","Stream");
    d=d.replace('\r',' ').replace('\n',' ');
    d=d.replaceAll("\\s+"," ").trim();
    return d;
  }
  String sourceExpandedDetails(JSONObject stream,String addon){
    StringBuilder data=new StringBuilder();
    data.append("Add-on: ").append(addon);
    String name=stream.optString("name","");
    if(!name.isEmpty())data.append("\nSource: ").append(name);
    String title=stream.optString("title","");
    if(!title.isEmpty())data.append("\nFull file details:\n").append(title);
    String description=stream.optString("description","");
    if(!description.isEmpty()&&!description.equals(title))data.append("\nDescription: ").append(description);
    JSONObject hints=stream.optJSONObject("behaviorHints");
    if(hints!=null){
      String file=hints.optString("filename","");
      if(!file.isEmpty())data.append("\nFilename: ").append(file);
      long size=hints.optLong("videoSize",0);
      if(size>0)data.append("\nFile size: ").append(streamSize(stream));
      String group=hints.optString("bingeGroup","");
      if(!group.isEmpty())data.append("\nStream group: ").append(group);
      if(hints.optBoolean("notWebReady",false))data.append("\nThis source may require a streaming server.");
    }
    JSONArray subtitles=stream.optJSONArray("subtitles");
    if(subtitles!=null&&subtitles.length()>0){
      data.append("\nSubtitles: ");
      for(int i=0;i<subtitles.length()&&i<8;i++){
        JSONObject sub=subtitles.optJSONObject(i);
        if(sub!=null){if(i>0)data.append(", ");data.append(sub.optString("lang","unknown"));}
      }
    }
    if(stream.has("infoHash"))data.append("\nTorrent source (no direct HTTP playback unless a streaming server provides a URL).");
    if(stream.has("fileIdx"))data.append("\nTorrent file index: ").append(stream.optInt("fileIdx",-1));
    String url=stream.optString("url","");
    if(!url.isEmpty()){
      try{data.append("\nStream host: ").append(new java.net.URL(url).getHost());}catch(Exception ignored){}
    }
    return data.toString();
  }
  void drawSources(){
    if(sourcePanel==null||!"Streams".equals(page)||!visibleStreamTitle.equals(selectedType+"|"+selectedId))return;
    sourcePanel.removeAllViews();
    final boolean showVlc=vlcIntegrationEnabled();
    int entries=0,playable=0,low=0,high=0;
    for(JSONObject response:visibleSources.values()){
      JSONArray arr=response==null?null:response.optJSONArray("streams");
      if(arr==null)continue;
      entries+=arr.length();
      for(int i=0;i<arr.length();i++){
        JSONObject v=arr.optJSONObject(i);if(v==null)continue;
        if(v.optString("url","").startsWith("http"))playable++;
        if("720p".equals(streamQuality(v)))low++;
        if("1080p".equals(streamQuality(v)))high++;
      }
    }
    if(sourceStatus!=null){
      sourceStatus.setText(playable+" playable sources • "+low+" at 720p • "+high+" at 1080p • "+sourceRequests+" add-ons loading");
    }
    if(entries==0&&sourceRequests>0){sourcePanel.addView(text("Loading sources from your add-ons…",15));return;}
    if(entries==0&&sourceRequests==0){sourcePanel.addView(text("No streams returned. Try another title or refresh add-ons.",16));return;}
    for(Map.Entry<String,JSONObject> record:visibleSources.entrySet()){
      final String addon=record.getKey();
      JSONObject response=record.getValue();
      JSONArray raw=response==null?null:response.optJSONArray("streams");if(raw==null)continue;
      ArrayList<JSONObject> items=new ArrayList<JSONObject>();
      for(int i=0;i<raw.length();i++){
        JSONObject stream=raw.optJSONObject(i);
        if(stream==null)continue;
        String quality=streamQuality(stream);
        if(!"All".equals(sourceFilter)){
          if("Other".equals(sourceFilter)){
            if("720p".equals(quality)||"1080p".equals(quality))continue;
          }else if(!sourceFilter.equals(quality))continue;
        }
        items.add(stream);
      }
      if(items.isEmpty())continue;
      java.util.Collections.sort(items,new java.util.Comparator<JSONObject>(){
        public int compare(JSONObject a,JSONObject b){
          int x=streamPriority(a)-streamPriority(b);
          if(x!=0)return x;
          return streamSize(a).compareTo(streamSize(b));
        }
      });
      TextView heading=text(addon.replaceAll(" #\\d+$","")+"  •  "+items.size()+" sources",16);
      heading.setTextColor(accent);heading.setTypeface(null,1);
      heading.setPadding(dp(8),dp(7),dp(8),dp(5));sourcePanel.addView(heading);
      int count=0;
      for(JSONObject stream:items){
        if(count++>=100){sourcePanel.addView(text("Showing the first 100 matches for this add-on.",13));break;}
        final String url=stream.optString("url","");
        final boolean direct=url.startsWith("http://")||url.startsWith("https://");
        LinearLayout card=new LinearLayout(this);card.setOrientation(1);
        card.setPadding(dp(10),dp(5),dp(10),dp(6));
        card.setBackgroundDrawable(shape(surface,8));
        LinearLayout row=new LinearLayout(this);row.setOrientation(0);row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout details=new LinearLayout(this);details.setOrientation(1);
        String tags=sourceTags(stream);
        if("720p".equals(streamQuality(stream)))tags="✓ RECOMMENDED  •  "+tags;
        String flags=FlagText.indicators(stream.optString("name","")+" "+stream.optString("title",""));
        if(!flags.isEmpty())tags+="  •  "+flags;
        TextView h=text(tags,15);h.setText(FlagText.decorate(this,tags));
        h.setTypeface(null,1);h.setTextColor("720p".equals(streamQuality(stream))?accent:Color.WHITE);
        h.setPadding(dp(6),dp(2),dp(6),dp(2));details.addView(h);
        String summary=firstInfoLine(stream);
        TextView about=text(summary,13);about.setText(FlagText.decorate(this,summary));about.setMaxLines(2);
        about.setEllipsize(android.text.TextUtils.TruncateAt.END);
        about.setPadding(dp(6),0,dp(6),dp(2));details.addView(about);
        row.addView(details,new LinearLayout.LayoutParams(0,-2,1));
        Button play=btn(direct?"▶ Watch":"Unavailable",new Runnable(){public void run(){if(direct)openInAppPlayer(url);}});
        play.setEnabled(direct);play.setTextSize(showVlc?14:17);
        if(!showVlc){
          play.setTypeface(null,1);
          play.setBackgroundDrawable(shape(accent,10));
          play.setTextColor(0xff071312);
        }
        // Reclaim the VLC button's space for a larger, high-contrast primary action.
        row.addView(play,new LinearLayout.LayoutParams(dp(showVlc?111:188),dp(showVlc?58:66)));
        if(showVlc){
          Button alternative=btn("VLC",new Runnable(){public void run(){if(direct)openVlc(url);}});
          alternative.setTextSize(12);alternative.setEnabled(direct);
          row.addView(alternative,new LinearLayout.LayoutParams(dp(66),dp(58)));
        }
        String expandedCopy=sourceExpandedDetails(stream,addon);
        final TextView expanded=text(expandedCopy,13);
        expanded.setText(FlagText.decorate(this,expandedCopy));
        expanded.setPadding(dp(10),dp(4),dp(8),dp(5));
        expanded.setVisibility(View.GONE);
        Button info=btn("Details",new Runnable(){public void run(){
          expanded.setVisibility(expanded.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);
        }});
        info.setTextSize(12);
        row.addView(info,new LinearLayout.LayoutParams(dp(70),dp(58)));
        card.addView(row);
        card.addView(expanded);
        sourcePanel.addView(card);
        View gap=new View(this);sourcePanel.addView(gap,new LinearLayout.LayoutParams(-1,dp(5)));
      }
    }
    if(sourcePanel.getChildCount()==0)sourcePanel.addView(text("No "+sourceFilter+" sources found. Choose All to see the others.",15));
  }
  void updateQualityButtons(){
    for(Map.Entry<String,Button> entry:qualityButtons.entrySet()){
      boolean active=entry.getKey().equals(sourceFilter);
      entry.getValue().setBackgroundDrawable(shape(active?accent:surface,8));
      entry.getValue().setTextColor(active?0xff071312:Color.WHITE);
    }
  }
  void loadStreams(){
    qualityButtons.clear();
    final String key=selectedType+"|"+selectedId;
    visibleStreamTitle=key;
    visibleSources.clear();
    sourceRequests=0;
    TextView heading=text(selectedName.isEmpty()?selectedId:selectedName,21);
    heading.setTypeface(null,1);heading.setPadding(dp(8),dp(1),dp(8),dp(2));add(heading);
    TextView tip=text(vlcIntegrationEnabled()?
      "720p H.264 recommended • Watch plays inside Stremio Auto • one Back returns here • VLC fallback for unsupported streams.":
      "720p H.264 recommended • Watch plays inside Stremio Auto • one Back returns here.",13);
    tip.setPadding(dp(8),dp(1),dp(8),dp(5));add(tip);
    LinearLayout controls=new LinearLayout(this);controls.setOrientation(0);
    for(final String choice:new String[]{"All","720p","1080p","Other"}){
      Button toggle=btn(choice,new Runnable(){public void run(){sourceFilter=choice;updateQualityButtons();drawSources();}});
      toggle.setTextSize(13);
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(39),1);lp.setMargins(dp(2),0,dp(2),dp(3));
      qualityButtons.put(choice,toggle);
      controls.addView(toggle,lp);
    }
    add(controls);
    updateQualityButtons();
    sourceStatus=text("Checking source add-ons…",13);
    sourceStatus.setPadding(dp(8),dp(3),dp(8),dp(5));add(sourceStatus);
    sourcePanel=new LinearLayout(this);sourcePanel.setOrientation(1);add(sourcePanel);
    if(addons.length()==0){
      sourcePanel.addView(text("No synced add-ons. Open Account and refresh add-ons.",16));
      return;
    }
    for(int i=0;i<addons.length();i++){
      JSONObject a=addons.optJSONObject(i);if(a==null)continue;
      final String manifest=manifestUrl(a);
      String base=baseUrl(manifest);if(!base.startsWith("https://"))continue;
      JSONObject mf=a.optJSONObject("manifest");
      if(!supports(mf,"stream",selectedType,selectedId))continue;
      final String url;
      try{url=base+"stream/"+enc(selectedType)+"/"+enc(selectedId)+".json";}catch(Exception ignored){continue;}
      final String provider=mf==null?"Add-on "+(i+1):mf.optString("name","Add-on "+(i+1));
      // Keep unique names even if multiple installed add-ons share a display label.
      final String sourceKey=provider+" #"+(i+1);
      JSONObject cached=streamMemory.get(url);
      File disk=dataFile("stream_choices",url);
      if(cached==null&&disk.isFile()&&System.currentTimeMillis()-disk.lastModified()<15*60*1000L){
        cached=readJson(disk);if(cached!=null)streamMemory.put(url,cached);
      }
      if(cached!=null){visibleSources.put(sourceKey,cached);continue;}
      if(streamPending.contains(url))continue;
      sourceRequests++;
      streamPending.add(url);
      apiWorkers.execute(new Runnable(){public void run(){
        try{
          final JSONObject reply=get(url);
          cacheJson(dataFile("stream_choices",url),reply);
          runOnUiThread(new Runnable(){public void run(){
            streamPending.remove(url);streamMemory.put(url,reply);
            if("Streams".equals(page)&&key.equals(visibleStreamTitle)){
              visibleSources.put(sourceKey,reply);
              if(sourceRequests>0)sourceRequests--;
              drawSources();
            }
          }});
        }catch(final Exception ex){
          runOnUiThread(new Runnable(){public void run(){
            streamPending.remove(url);
            if("Streams".equals(page)&&key.equals(visibleStreamTitle)){
              if(sourceRequests>0)sourceRequests--;
              JSONObject problem=new JSONObject();
              try{problem.put("streams",new JSONArray());}catch(Exception ignored){}
              visibleSources.put(sourceKey+" • unavailable",problem);
              drawSources();
            }
            recordError(new IOException("Source "+provider+": "+ex.getClass().getSimpleName()));
          }});
        }
      }});
    }
    drawSources();
    showManualProgress();
  }
  void openInAppPlayer(final String url){
    if(!parkedConfirmed){showParkedConfirmation();return;}
    if(prefs.getBoolean("motion_lockout",false)&&motionLocked){refreshMotionDialog();return;}
    if(url==null||(!url.startsWith("http://")&&!url.startsWith("https://"))){
      message("This source does not provide a direct video URL.");return;
    }
    rememberTitle();
    prefs.edit().putString("last_page","Streams").apply();
    try{
      Intent next=new Intent(this,ExoPlayerActivity.class);
      next.putExtra("parked_confirmed",parkedConfirmed);
      next.putExtra("stream_url",url);
      next.putExtra("stream_title",selectedName);
      startActivityForResult(next,103);
    }catch(Throwable problem){
      message("In-app player unavailable: "+problem.getClass().getSimpleName()+
        (vlcIntegrationEnabled()?". Use VLC fallback.":". Try another source."));
    }
  }
  void openWithOtherPlayer(final String url){
    if(!parkedConfirmed){showParkedConfirmation();return;}
    try{
      Intent play=new Intent(Intent.ACTION_VIEW);play.setDataAndType(android.net.Uri.parse(url),"video/*");
      Intent choose=Intent.createChooser(play,"Select a video player");
      rememberTitle();
      prefs.edit().putBoolean("external_playback",true).putString("last_page","Streams").apply();
      startVideoMeasurement();returningFromPlayer=true;
      startActivityForResult(choose,101);
    }catch(Exception e){message("No compatible alternative player is installed. VLC remains available.");}
  }
  void openVlc(final String link){
    if(!vlcIntegrationEnabled())return;
    if(!parkedConfirmed){showParkedConfirmation();return;}
    try{
      Intent intent=new Intent(Intent.ACTION_VIEW);
      intent.setDataAndType(android.net.Uri.parse(link),"video/*");
      intent.setPackage("org.videolan.vlc");
      intent.putExtra("title",selectedName);
      startVideoMeasurement();
      returningFromPlayer=true;
      rememberTitle();
      prefs.edit().putBoolean("external_playback",true).putString("last_page","Streams").apply();
      startActivityForResult(intent,101);
    }catch(Exception e){
      returningFromPlayer=false;videoBaseline=null;prefs.edit().putBoolean("external_playback",false).apply();
      message("Unable to launch VLC. Check that VLC is installed and the link is playable.");
    }
  }
  @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
    super.onActivityResult(requestCode,resultCode,data);
    if(requestCode==103){
      String fallback=data==null?"":data.getStringExtra("fallback_vlc_url");
      if(vlcIntegrationEnabled()&&fallback!=null&&!fallback.isEmpty()){openVlc(fallback);return;}
      if(data!=null&&resultCode==RESULT_OK){
        long position=data.getLongExtra("extra_position",-1L);
        long duration=data.getLongExtra("extra_duration",-1L);
        if(position>10000&&duration>0&&position<=duration)saveProgress(position,duration,true);
      }
      if(!selectedId.isEmpty()&&!"Streams".equals(page))render("Streams",false);
      return;
    }
    if(requestCode==101){
      finishVideoMeasurement();returningFromPlayer=false;
      prefs.edit().putBoolean("external_playback",false).apply();
      if(data!=null){
        long position=data.getLongExtra("extra_position",-1L);
        long duration=data.getLongExtra("extra_duration",-1L);
        if(position>=0&&duration>position&&resultCode==RESULT_OK)saveProgress(position,duration,true);
      }
      // Keep the stream selection available when VLC returns; never jump to Home.
      if(!selectedId.isEmpty()&&!"Streams".equals(page))render("Streams",false);
    }
  }
  @Override protected void onNewIntent(Intent intent){
    super.onNewIntent(intent);
    setIntent(intent);
    if(intent!=null&&(intent.getBooleanExtra("return_to_streams",false)||prefs.getBoolean("external_playback",false))){
      if(!selectedId.isEmpty()){
        if(returningFromPlayer){finishVideoMeasurement();returningFromPlayer=false;}
        prefs.edit().putBoolean("external_playback",false).putString("last_page","Streams").apply();
        render("Streams",false);
      }
    }
  }
  @Override protected void onResume(){
    super.onResume();
    mainResumed=true;
    immersiveWindow();
    updateMotionMonitoring();
    if(returningFromPlayer){
      finishVideoMeasurement();returningFromPlayer=false;
      prefs.edit().putBoolean("external_playback",false).putString("last_page","Streams").apply();
      if(!selectedId.isEmpty()&&!"Streams".equals(page))render("Streams",false);
    }
    if(!auth.isEmpty()){
      refreshLibrary(false);refreshAddons(false);
    }
  }
  @Override protected void onPause(){
    mainResumed=false;
    if(motionGuard!=null)motionGuard.stop();
    super.onPause();
  }
  @Override protected void onDestroy(){
    if(motionGuard!=null){motionGuard.stop();motionGuard=null;}
    if(motionDialog!=null){motionDialog.dismiss();motionDialog=null;}
    if(parkedDialog!=null){parkedDialog.dismiss();parkedDialog=null;}
    refreshHandler.removeCallbacks(homeRefresh);
    apiWorkers.shutdownNow();catalogWorkers.shutdownNow();sportsWorkers.shutdownNow();imageWorkers.shutdownNow();
    super.onDestroy();
  }

  void back(){if(!history.isEmpty())render(history.remove(history.size()-1),false);else if(!page.equals("Home"))render("Home",false);}
  public void onBackPressed(){back();}
  public boolean dispatchTouchEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN){sx=e.getX();sy=e.getY();}if(e.getAction()==MotionEvent.ACTION_UP&&sx<dp(55)&&e.getX()-sx>dp(110)&&Math.abs(e.getY()-sy)<dp(90)){back();return true;}return super.dispatchTouchEvent(e);}
}
