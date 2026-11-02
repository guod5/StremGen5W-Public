package com.gen5.stremio;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Optional Android 4.4 GPS movement soft lock.
 * No location coordinates are persisted or transmitted by this class.
 * GPS movement is not a vehicle gear-position signal.
 */
public final class MotionGuard implements LocationListener {
  public interface Listener {
    void onMotionStateChanged(boolean locked, String reason);
  }

  public static final float LIMIT_MPH=2.0f;
  private static final float LIMIT_MPS=0.89408f;
  private static final long LOCATION_TIMEOUT_MS=12000L;
  private static final long STATIONARY_MS=8000L;
  private final LocationManager manager;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final Listener listener;
  private boolean active=false,locked=true;
  private long startedAt=0,lastGoodFix=0,stationarySince=0;
  private String reason="Waiting for GPS...";
  private float speedMph=0f;

  public MotionGuard(Context context,Listener callback){
    manager=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
    listener=callback;
  }

  public boolean isLocked(){return locked;}
  public String getReason(){return reason;}
  public float getSpeedMph(){return speedMph;}

  private void report(boolean newLocked,String message){
    if(!active)return;
    boolean changed=(newLocked!=locked)||!reason.equals(message);
    locked=newLocked;
    reason=message;
    if(changed&&listener!=null)listener.onMotionStateChanged(locked,reason);
  }

  private final Runnable watchdog=new Runnable(){
    @Override public void run(){
      if(!active)return;
      long now=SystemClock.elapsedRealtime();
      if(lastGoodFix==0){
        if(now-startedAt>15000L)report(true,"GPS unavailable. Check location reception or switch off Motion lockout in Settings.");
      }else if(now-lastGoodFix>LOCATION_TIMEOUT_MS){
        stationarySince=0;
        report(true,"GPS signal lost. Playback will remain paused until a stable location fix returns.");
      }
      handler.postDelayed(this,2000L);
    }
  };

  public void start(){
    if(active)return;
    active=true;
    locked=true;
    reason="Waiting for GPS...";
    speedMph=0f;
    startedAt=SystemClock.elapsedRealtime();
    lastGoodFix=0;
    stationarySince=0;
    if(listener!=null)listener.onMotionStateChanged(true,reason);
    if(manager==null){
      report(true,"GPS provider is unavailable on this device.");
    }else{
      try{
        if(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)){
          manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1500L,0f,this,Looper.getMainLooper());
        }else{
          report(true,"GPS is disabled or unavailable. Check Settings.");
        }
      }catch(SecurityException problem){
        report(true,"Location permission is unavailable.");
      }catch(IllegalArgumentException problem){
        report(true,"GPS provider is not supported on this head unit.");
      }
    }
    handler.postDelayed(watchdog,2000L);
  }

  public void stop(){
    if(!active)return;
    active=false;
    handler.removeCallbacks(watchdog);
    if(manager!=null){
      try{manager.removeUpdates(this);}catch(SecurityException ignored){}
    }
  }

  @Override public void onLocationChanged(Location point){
    if(!active||point==null)return;
    long ageNs=SystemClock.elapsedRealtimeNanos()-point.getElapsedRealtimeNanos();
    if(ageNs<0||ageNs>LOCATION_TIMEOUT_MS*1000000L)return;
    if(!point.hasAccuracy()||point.getAccuracy()>60f||!point.hasSpeed())return;
    long now=SystemClock.elapsedRealtime();
    lastGoodFix=now;
    float speed=point.getSpeed();
    speedMph=Math.max(0f,speed*2.2369363f);
    if(speed>=LIMIT_MPS){
      stationarySince=0;
      report(true,String.format(java.util.Locale.US,"Motion detected: %.1f mph (limit 2 mph).",speedMph));
    }else{
      if(stationarySince==0)stationarySince=now;
      if(now-stationarySince>=STATIONARY_MS){
        report(false,"GPS reports stationary. You may resume playback.");
      }else{
        report(true,"Waiting for a stable stationary GPS reading...");
      }
    }
  }

  @Override public void onProviderEnabled(String provider){
    if(LocationManager.GPS_PROVIDER.equals(provider)&&active){
      lastGoodFix=0;
      stationarySince=0;
      startedAt=SystemClock.elapsedRealtime();
      report(true,"GPS enabled. Waiting for a stable fix...");
    }
  }

  @Override public void onProviderDisabled(String provider){
    if(LocationManager.GPS_PROVIDER.equals(provider)){
      stationarySince=0;
      lastGoodFix=0;
      report(true,"GPS disabled. Playback is locked while Motion lockout is enabled.");
    }
  }

  @Override public void onStatusChanged(String provider,int status,Bundle extras){
    // Fresh location timestamps are more reliable than provider status on Android 4.4.
  }
}
