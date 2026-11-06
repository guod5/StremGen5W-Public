package com.gen5.stremio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * Sends real ExoPlayer playback to the signed-in Stremio library datastore.
 *
 * Stremio stores one LibraryItem per movie or series, with state.video_id
 * pointing to the current episode. Recently watched titles not explicitly
 * saved to Library are temporary items (removed=true, temp=true).
 *
 * Never put HTTP work on the media/UI thread. All writes are serialized, so
 * an older tick cannot overwrite a newer position. This class retains no
 * Activity or sensitive account data in its fields.
 */
final class PlaybackSync {
  private final Context app;
  private final String titleId, mediaType, videoId, title, poster;
  private final ExecutorService queue=Executors.newSingleThreadExecutor();
  private long lastQueuedAt=0;
  private long confirmedWatchedMs=0;

  PlaybackSync(Context context,String metaId,String type,String playingVideoId,
               String itemTitle,String itemPoster){
    app=context.getApplicationContext();
    titleId=metaId==null?"":metaId.trim();
    mediaType=type==null?"":type.trim();
    videoId=playingVideoId==null?"":playingVideoId.trim();
    title=itemTitle==null?"":itemTitle.trim();
    poster=itemPoster==null?"":itemPoster.trim();
  }

  boolean hasIdentity(){
    if(titleId.isEmpty()||videoId.isEmpty())return false;
    if(!"movie".equals(mediaType)&&!"series".equals(mediaType))return false;
    // Do not invent an episode ID and pollute cross-device series history.
    return !"series".equals(mediaType)||!titleId.equals(videoId);
  }

  synchronized void record(final long positionMs,final long durationMs,
                           final long watchedMs,boolean finalUpdate){
    if(!hasIdentity()){
      status("Not synced: missing movie or episode ID. Check the selected title.");
      return;
    }
    if(positionMs<5000||durationMs<=0||durationMs>24L*60*60*1000)return;
    long now=SystemClock.elapsedRealtime();
    if(!finalUpdate&&lastQueuedAt>0&&now-lastQueuedAt<30000)return;
    lastQueuedAt=now;
    final long position=Math.min(positionMs,durationMs);
    final long duration=durationMs;
    final long watched=Math.max(0,watchedMs);
    queue.execute(new Runnable(){public void run(){
      try{
        save(position,duration,watched);
      }catch(Exception e){
        status("Not synced: "+e.getClass().getSimpleName()+". Check your connection and account.");
      }
    }});
  }

  void finish(){
    // Previously queued writes finish even after the Activity exits.
    queue.shutdown();
  }

  private void status(String value){
    app.getSharedPreferences("account",0).edit()
      .putString("cloud_sync_status",value).apply();
  }

  private static String utcNow(){
    SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f.format(new Date());
  }

  private void save(long position,long duration,long watched)throws Exception{
    SharedPreferences prefs=app.getSharedPreferences("account",0);
    String auth=prefs.getString("auth","");
    if(auth.isEmpty()){
      status("Not synced: sign in to your Stremio account.");
      return;
    }

    JSONObject find=new JSONObject();
    find.put("authKey",auth);
    find.put("collection","libraryItem");
    find.put("ids",new JSONArray().put(titleId));
    find.put("all",false);
    JSONObject response=post("datastoreGet",find);
    JSONArray found=items(response);
    JSONObject old=null;
    for(int i=0;i<found.length();i++){
      JSONObject entry=found.optJSONObject(i);
      if(entry!=null&&titleId.equals(entry.optString("_id",entry.optString("id","")))){
        old=entry;break;
      }
    }

    JSONObject item=old==null?new JSONObject():new JSONObject(old.toString());
    String now=utcNow();
    if(old==null){
      item.put("_ctime",now);
      // Stremio Core models watch-only entries as temporary and removed from
      // the explicit Library, but visible in Continue Watching.
      item.put("removed",true);
      item.put("temp",true);
      item.put("posterShape","poster");
      item.put("behaviorHints",new JSONObject());
    }
    if(item.optBoolean("removed",false)&&!item.optBoolean("temp",false)){
      // Resuming a previously removed title must appear in Continue Watching.
      item.put("temp",true);
    }
    item.put("_id",titleId);
    item.put("_mtime",now);
    item.put("type",mediaType);
    if(old==null||item.optString("name","").isEmpty())
      item.put("name",title.isEmpty()?titleId:title);
    if(old==null||item.optString("poster","").isEmpty())
      item.put("poster",poster);

    JSONObject state=item.optJSONObject("state");
    if(state==null)state=new JSONObject();
    boolean differentVideo=!videoId.equals(state.optString("video_id",""));
    long previousWatched=differentVideo?0:Math.max(0,state.optLong("timeWatched",0));
    long overall=Math.max(0,state.optLong("overallTimeWatched",0));
    // Match Stremio Core's rollover when starting another episode.
    if(differentVideo)overall+=Math.max(0,state.optLong("timeWatched",0));
    long addition=Math.max(0,watched-confirmedWatchedMs);
    // The player counts only actual play time, never time skipped by seeking.
    state.put("timeWatched",previousWatched+addition);
    state.put("overallTimeWatched",overall+addition);
    state.put("timeOffset",position);
    state.put("duration",duration);
    state.put("video_id",videoId);
    state.put("lastWatched",now);
    if(differentVideo)state.put("flaggedWatched",0);
    if(!state.has("timesWatched"))state.put("timesWatched",0);
    if(!state.has("noNotif"))state.put("noNotif",false);
    if(!state.has("flaggedWatched"))state.put("flaggedWatched",0);

    // Stremio Core uses 70% of actual watched time (not seek position).
    if(state.optInt("flaggedWatched",0)==0 &&
       state.optLong("timeWatched",0)>duration*0.7d){
      state.put("flaggedWatched",1);
      state.put("timesWatched",state.optInt("timesWatched",0)+1);
    }
    item.put("state",state);

    JSONObject put=new JSONObject();
    put.put("authKey",auth);
    put.put("collection","libraryItem");
    put.put("changes",new JSONArray().put(item));
    JSONObject saved=post("datastorePut",put);
    JSONObject result=saved.optJSONObject("result");
    if(result==null||!result.optBoolean("success",false))
      throw new IOException("Stremio did not confirm library write");
    confirmedWatchedMs=watched;
    status("Synced to Stremio • "+utcNow()+" • "+titleId);
  }

  private static JSONArray items(JSONObject response)throws IOException{
    Object data=response.opt("result");
    if(data instanceof JSONArray)return (JSONArray)data;
    if(data instanceof JSONObject){
      JSONObject result=(JSONObject)data;
      JSONArray list=result.optJSONArray("items");
      if(list==null)list=result.optJSONArray("data");
      if(list==null)list=result.optJSONArray("result");
      if(list!=null)return list;
    }
    throw new IOException("Unexpected Stremio library response");
  }

  private static JSONObject post(String method,JSONObject data)throws Exception{
    HttpsURLConnection c=(HttpsURLConnection)
      new URL("https://api.strem.io/api/"+method).openConnection();
    c.setConnectTimeout(15000);
    c.setReadTimeout(20000);
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    c.setRequestProperty("Content-Type","application/json");
    c.setRequestProperty("Accept-Encoding","gzip");
    c.setSSLSocketFactory(new MainActivity.Tls12Factory(
      (SSLSocketFactory)SSLSocketFactory.getDefault()));
    try{
      byte[] payload=data.toString().getBytes("UTF-8");
      OutputStream out=c.getOutputStream();
      try{out.write(payload);}finally{out.close();}
      int http=c.getResponseCode();
      InputStream in=http>=400?c.getErrorStream():c.getInputStream();
      if(in==null)throw new IOException("Empty Stremio response");
      if("gzip".equalsIgnoreCase(c.getContentEncoding()))in=new GZIPInputStream(in);
      ByteArrayOutputStream buf=new ByteArrayOutputStream();
      byte[] bytes=new byte[8192];
      int n;
      try{
        while((n=in.read(bytes))!=-1){
          buf.write(bytes,0,n);
          if(buf.size()>4*1024*1024)throw new IOException("Response too large");
        }
      }finally{in.close();}
      if(http>=400)throw new IOException("Stremio HTTP "+http);
      JSONObject response=new JSONObject(buf.toString("UTF-8"));
      if(response.has("error")&&!response.isNull("error"))
        throw new IOException("Stremio API error");
      if(!response.has("result"))throw new IOException("Stremio response has no result");
      return response;
    }finally{c.disconnect();}
  }
}
