package com.gen5.stremio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;
import android.widget.ScrollView;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoListener;

import java.util.ArrayList;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * Gen5W in-app player, Android API 19.
 *
 * Tap video to toggle a full seekable playback overlay. It auto-hides during
 * playback. System Back, edge swipe and Back to Sources return immediately.
 * Keeps the bundled Mozilla CA trust fix and never disables TLS verification.
 *
 * Audio uses Android's media stream and explicitly requests audio focus. Users
 * can select audio and subtitle tracks exposed by the source; this cannot
 * conjure a decoder for an unsupported AC3/EAC3/DTS-only soundtrack.
 */
public class ExoPlayerActivity extends Activity {
  private SimpleExoPlayer player;
  private DefaultTrackSelector selector;
  private SSLSocketFactory originalTls;
  private AudioManager audioManager;
  private boolean audioFocusGranted=false;
  private boolean released=false,firstFrame=false,controlsVisible=true,seeking=false;
  private long lastPosition=0,lastDuration=0,openedAt=0;
  private String streamUrl="",videoTitle="";
  private FrameLayout root;
  private PlayerView video;
  private View brightnessShade;
  private FrameLayout activeTrackOverlay;
  private int pictureBrightness=50;
  private LinearLayout topPanel,bottomPanel,transportPanel;
  private TextView title,positionLabel,durationLabel,status;
  private Button pauseButton,aspectButton,audioButton,subtitleButton;
  private SeekBar seek;
  private int aspect=0;
  private boolean userPaused=false,autoplayStarted=false;
  private float savedWindowBrightness=WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
  private boolean trackMessageShown=false;
  private float fingerX,fingerY;
  private final Handler handler=new Handler();
  private static final int TEAL=0xff35dfc7, WHITE=Color.WHITE, PANEL=0xdc121824;
  private static final int MUTED=0xffb0bfcb, PANEL_BASE=0xff131c29, BORDER=0xff3a5265;

  private final AudioManager.OnAudioFocusChangeListener focusListener=new AudioManager.OnAudioFocusChangeListener(){
    public void onAudioFocusChange(int focus){
      if(player==null||released)return;
      if(focus==AudioManager.AUDIOFOCUS_LOSS || focus==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){
        player.setPlayWhenReady(false);showControls(true);
      }else if(focus==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK){
        player.setVolume(0.25f);
      }else if(focus==AudioManager.AUDIOFOCUS_GAIN){
        player.setVolume(1f);
      }
    }
  };
  private final Runnable hideRunnable=new Runnable(){public void run(){
    if(player!=null&&!released&&player.getPlayWhenReady()&&!seeking)showControls(false);
  }};
  private final Runnable progressRunnable=new Runnable(){public void run(){
    if(released||player==null)return;
    try{
      long p=player.getCurrentPosition(),d=player.getDuration();
      if(p>=0)lastPosition=p;
      if(d>0&&d!=C.TIME_UNSET)lastDuration=d;
      if(!seeking){
        if(seek!=null&&lastDuration>0){
          seek.setProgress((int)Math.min(1000,lastPosition*1000L/lastDuration));
          long buffered=player.getBufferedPosition();
          seek.setSecondaryProgress((int)Math.min(1000,Math.max(0,buffered)*1000L/lastDuration));
        }
        if(positionLabel!=null)positionLabel.setText(time(lastPosition));
        if(durationLabel!=null)durationLabel.setText(time(lastDuration));
      }
      if(pauseButton!=null)pauseButton.setText(player.getPlayWhenReady()?"Ⅱ":"▶");
      if(!firstFrame && System.currentTimeMillis()-openedAt>22000 &&
        player.getPlaybackState()==Player.STATE_BUFFERING && status!=null){
        status.setText("Still buffering. Select a different source or try VLC.");
        status.setVisibility(View.VISIBLE);
      }
    }catch(Throwable ignored){}
    handler.postDelayed(this,750);
  }};

  private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
  private String time(long ms){
    if(ms<0)return "--:--";
    long sec=ms/1000;
    return String.format(Locale.US,"%02d:%02d:%02d",sec/3600,sec/60%60,sec%60);
  }
  private TextView text(String value,int sp){
    TextView t=new TextView(this);t.setText(value);t.setTextColor(WHITE);
    t.setTextSize(sp);t.setGravity(Gravity.CENTER_VERTICAL);
    t.setPadding(dp(8),dp(2),dp(8),dp(2));return t;
  }
  private GradientDrawable pill(int bg,int stroke,int radius){
    GradientDrawable d=new GradientDrawable();
    d.setShape(GradientDrawable.RECTANGLE);d.setColor(bg);d.setCornerRadius(dp(radius));
    if(stroke!=0)d.setStroke(dp(1),stroke);
    return d;
  }
  private GradientDrawable shade(boolean atTop){
    int[] colors=atTop?
      new int[]{0xed07131d,0xc9091721,0x00091722}:
      new int[]{0x00091722,0xd10c1721,0xf5091721};
    return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,colors);
  }
  private Button button(String label,View.OnClickListener listener){
    Button b=new Button(this);
    b.setText(label);b.setTextSize(13);b.setAllCaps(false);
    b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
    b.setPadding(dp(7),0,dp(7),0);
    b.setTextColor(WHITE);b.setBackgroundDrawable(pill(0xbb253442,BORDER,12));
    b.setOnClickListener(listener);
    return b;
  }
  private Button tool(String label,View.OnClickListener listener){
    Button b=button(label,listener);
    b.setTextSize(13);
    b.setBackgroundDrawable(pill(0xc21d2e3b,0xff304957,12));
    return b;
  }
  private Button roundControl(String symbol,int size,boolean primary,View.OnClickListener listener){
    Button b=button(symbol,listener);
    b.setTextSize(primary?32:21);
    b.setTextColor(primary?0xff091b21:WHITE);
    b.setBackgroundDrawable(pill(primary?TEAL:0xe6223445,
      primary?0:0xff68808a,size/2));
    return b;
  }
  private void spaced(LinearLayout row,Button b,int width,int height,int space){
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(width),dp(height));
    lp.setMargins(dp(space),0,dp(space),0);
    row.addView(b,lp);
  }
  private TextView small(String value){
    TextView t=text(value,12);t.setTextColor(MUTED);return t;
  }
  private void scheduleHide(){
    handler.removeCallbacks(hideRunnable);
    if(controlsVisible&&player!=null&&player.getPlayWhenReady()&&!seeking){
      handler.postDelayed(hideRunnable,4800);
    }
  }
  private void showControls(boolean visible){
    controlsVisible=visible;
    if(topPanel!=null)topPanel.setVisibility(visible?View.VISIBLE:View.GONE);
    if(bottomPanel!=null)bottomPanel.setVisibility(visible?View.VISIBLE:View.GONE);
    if(transportPanel!=null)transportPanel.setVisibility(visible?View.VISIBLE:View.GONE);
    handler.removeCallbacks(hideRunnable);
    if(visible)scheduleHide();
  }
  private void status(String value){
    if(status==null)return;
    status.setText(value);status.setVisibility(View.VISIBLE);
  }
  private void clearStatus(){
    if(status!=null)status.setVisibility(View.GONE);
  }
  private void updateAspect(){
    if(video==null)return;
    int mode=aspect==0?AspectRatioFrameLayout.RESIZE_MODE_FIT:
      aspect==1?AspectRatioFrameLayout.RESIZE_MODE_ZOOM:AspectRatioFrameLayout.RESIZE_MODE_FILL;
    video.setResizeMode(mode);
    if(aspectButton!=null){
      aspectButton.setText(aspect==0?"▣  Fit":aspect==1?"▣  Crop":"▣  Stretch");
    }
  }
  // Android may ignore per-window backlight changes in the car compositor.
  // Add a picture-only tint as a visible fallback without changing system settings.
  private void applyPictureBrightness(int value){
    pictureBrightness=Math.max(0,Math.min(100,value));
    if(brightnessShade!=null){
      int alpha=pictureBrightness<50?Math.round((50-pictureBrightness)*3.5f):
        Math.round((pictureBrightness-50)*1.45f);
      int color=pictureBrightness<50?Color.BLACK:Color.WHITE;
      brightnessShade.setBackgroundColor((Math.min(180,alpha)<<24)|(color&0x00ffffff));
    }
    WindowManager.LayoutParams params=getWindow().getAttributes();
    params.screenBrightness=pictureBrightness==50?savedWindowBrightness:
      Math.max(0.02f,Math.min(1f,pictureBrightness/100f));
    getWindow().setAttributes(params);
  }
  private void showBrightness(){
    showControls(true);
    final Dialog modal=new Dialog(this);
    modal.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout panel=dialogShell("Picture Brightness","VIDEO ONLY • PREVIEW CHANGES LIVE");
    TextView note=small("50 = normal picture. Move left to dim or right to brighten the image. Your car may control the physical backlight separately.");
    note.setPadding(dp(14),dp(9),dp(14),dp(8));panel.addView(note);
    final TextView current=text("Brightness: "+pictureBrightness+"%",15);
    panel.addView(current);
    SeekBar slider=new SeekBar(this);slider.setMax(100);slider.setProgress(pictureBrightness);
    slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onProgressChanged(SeekBar bar,int value,boolean fromUser){
        if(fromUser){applyPictureBrightness(value);current.setText("Brightness: "+value+"%");}
      }
      public void onStartTrackingTouch(SeekBar bar){}
      public void onStopTrackingTouch(SeekBar bar){}
    });
    panel.addView(slider,new LinearLayout.LayoutParams(-1,dp(54)));
    Button reset=tool("Reset to normal",new View.OnClickListener(){public void onClick(View v){
      applyPictureBrightness(50);modal.dismiss();
    }});
    panel.addView(reset,new LinearLayout.LayoutParams(-1,dp(45)));
    Button done=tool("Done",new View.OnClickListener(){public void onClick(View v){modal.dismiss();}});
    panel.addView(done,new LinearLayout.LayoutParams(-1,dp(45)));
    modal.setContentView(panel);
    presentModal(modal);
    if(modal.getWindow()!=null){
      // Keep the video visible while the picture changes.
      modal.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }
  }
  private void immersiveWindow(){
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
  private void restoreBrightness(){
    WindowManager.LayoutParams params=getWindow().getAttributes();params.screenBrightness=savedWindowBrightness;getWindow().setAttributes(params);
  }
  private void flipAspect(){
    aspect=(aspect+1)%3;updateAspect();showControls(true);
  }
  private void seekBy(long delta){
    if(player==null)return;
    long next=Math.max(0,player.getCurrentPosition()+delta);
    if(lastDuration>0)next=Math.min(next,lastDuration);
    player.seekTo(next);showControls(true);
  }

  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);
    savedWindowBrightness=getWindow().getAttributes().screenBrightness;
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    immersiveWindow();
    setVolumeControlStream(AudioManager.STREAM_MUSIC);
    // The in-app player must only be started after the foreground parking acknowledgement.
    if(!getIntent().getBooleanExtra("parked_confirmed",false)){finish();return;}
    streamUrl=getIntent().getStringExtra("stream_url");
    videoTitle=getIntent().getStringExtra("stream_title");
    if(videoTitle==null)videoTitle="StremGen5W";
    if(streamUrl==null||(!streamUrl.startsWith("https://")&&!streamUrl.startsWith("http://"))){finish();return;}
    audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
    if(audioManager!=null){
      int focus=audioManager.requestAudioFocus(focusListener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);
      audioFocusGranted=(focus==AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    }

    root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
    video=new PlayerView(this);video.setUseController(false);
    video.setBackgroundColor(Color.BLACK);
    video.setKeepScreenOn(true);
    root.addView(video,new FrameLayout.LayoutParams(-1,-1));
    root.setFitsSystemWindows(false);
    video.setFitsSystemWindows(false);
    brightnessShade=new View(this);
    brightnessShade.setClickable(false);
    brightnessShade.setFocusable(false);
    root.addView(brightnessShade,new FrameLayout.LayoutParams(-1,-1));
    applyPictureBrightness(50);

    // PlayerView uses SurfaceView; this transparent layer receives taps without
    // preventing the video surface from rendering below the overlay.
    View touchLayer=new View(this);touchLayer.setBackgroundColor(Color.TRANSPARENT);
    touchLayer.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
      showControls(!controlsVisible);
    }});
    root.addView(touchLayer,new FrameLayout.LayoutParams(-1,-1));

    // VLC-style overlay: title at top, grouped playback controls in the
    // center, one readable timeline and small utilities at the lower edge.
    // No permanent chrome remains on screen while the film plays.
    topPanel=new LinearLayout(this);
    topPanel.setOrientation(0);
    topPanel.setGravity(Gravity.CENTER_VERTICAL);
    topPanel.setPadding(dp(19),dp(7),dp(22),dp(14));
    topPanel.setBackgroundDrawable(shade(true));
    Button back=button("‹  Sources",new View.OnClickListener(){public void onClick(View v){exit(null);}});
    back.setTextColor(TEAL);back.setTextSize(15);
    back.setBackgroundDrawable(pill(0xc0192c36,0xff335966,11));
    topPanel.addView(back,new LinearLayout.LayoutParams(dp(139),dp(45)));
    title=text(videoTitle,18);title.setTypeface(null,1);
    title.setPadding(dp(18),0,dp(10),0);
    title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    topPanel.addView(title,new LinearLayout.LayoutParams(0,dp(45),1));
    TextView label=small("STREMGEN5W  •  PLAYER");
    label.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
    topPanel.addView(label,new LinearLayout.LayoutParams(dp(165),dp(45)));
    root.addView(topPanel,new FrameLayout.LayoutParams(-1,dp(78),Gravity.TOP));

    transportPanel=new LinearLayout(this);
    transportPanel.setOrientation(0);
    transportPanel.setGravity(Gravity.CENTER);
    Button rewind=roundControl("↶",65,false,new View.OnClickListener(){public void onClick(View v){seekBy(-10000);}});
    rewind.setContentDescription("Rewind 10 seconds");
    spaced(transportPanel,rewind,62,62,12);
    pauseButton=roundControl("Ⅱ",76,true,new View.OnClickListener(){public void onClick(View v){
      if(player==null)return;
      userPaused=player.getPlayWhenReady();
      player.setPlayWhenReady(!userPaused);
      showControls(true);
    }});
    pauseButton.setContentDescription("Play or pause");
    spaced(transportPanel,pauseButton,77,77,13);
    Button forward=roundControl("↷",65,false,new View.OnClickListener(){public void onClick(View v){seekBy(30000);}});
    forward.setContentDescription("Forward 30 seconds");
    spaced(transportPanel,forward,62,62,12);
    FrameLayout.LayoutParams center=new FrameLayout.LayoutParams(-2,dp(83),Gravity.CENTER);
    root.addView(transportPanel,center);

    bottomPanel=new LinearLayout(this);
    bottomPanel.setOrientation(1);
    bottomPanel.setPadding(dp(13),dp(26),dp(19),dp(9));
    bottomPanel.setBackgroundDrawable(shade(false));
    LinearLayout seekLine=new LinearLayout(this);
    seekLine.setOrientation(0);seekLine.setGravity(Gravity.CENTER_VERTICAL);
    // The first 65dp is left free so the swipe-to-back region cannot
    // accidentally receive a seek gesture.
    seekLine.setPadding(dp(65),0,dp(12),0);
    positionLabel=text("00:00:00",13);
    positionLabel.setTextColor(WHITE);
    seekLine.addView(positionLabel,new LinearLayout.LayoutParams(dp(84),dp(38)));
    seek=new SeekBar(this);seek.setMax(1000);seek.setProgress(0);
    if(seek.getProgressDrawable()!=null)seek.getProgressDrawable()
      .setColorFilter(TEAL,android.graphics.PorterDuff.Mode.SRC_IN);
    if(seek.getThumb()!=null)seek.getThumb()
      .setColorFilter(TEAL,android.graphics.PorterDuff.Mode.SRC_IN);
    seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
      public void onStartTrackingTouch(SeekBar bar){
        seeking=true;handler.removeCallbacks(hideRunnable);showControls(true);
      }
      public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){
        if(fromUser&&positionLabel!=null&&lastDuration>0)
          positionLabel.setText(time(progress*lastDuration/1000L));
      }
      public void onStopTrackingTouch(SeekBar bar){
        if(player!=null&&lastDuration>0)player.seekTo((long)bar.getProgress()*lastDuration/1000);
        seeking=false;showControls(true);
      }
    });
    seekLine.addView(seek,new LinearLayout.LayoutParams(0,dp(38),1));
    durationLabel=text("--:--",13);durationLabel.setTextColor(MUTED);
    durationLabel.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
    seekLine.addView(durationLabel,new LinearLayout.LayoutParams(dp(84),dp(38)));
    bottomPanel.addView(seekLine);

    LinearLayout utilities=new LinearLayout(this);
    utilities.setOrientation(0);utilities.setGravity(Gravity.CENTER_VERTICAL);
    TextView hint=small("‹›  10s back / 30s forward");
    hint.setPadding(dp(65),0,0,0);
    utilities.addView(hint,new LinearLayout.LayoutParams(0,dp(43),1));
    audioButton=tool("♫  Audio",new View.OnClickListener(){public void onClick(View v){chooseTracks(C.TRACK_TYPE_AUDIO);}});
    spaced(utilities,audioButton,102,42,5);
    subtitleButton=tool("CC  Subtitles",new View.OnClickListener(){public void onClick(View v){chooseTracks(C.TRACK_TYPE_TEXT);}});
    spaced(utilities,subtitleButton,124,42,5);
    aspectButton=tool("▣  Fit",new View.OnClickListener(){public void onClick(View v){flipAspect();}});
    spaced(utilities,aspectButton,92,42,5);
    Button brightnessButton=tool("☀  Light",new View.OnClickListener(){public void onClick(View v){showBrightness();}});
    spaced(utilities,brightnessButton,94,42,5);
    Button vlc=tool("VLC  ↗",new View.OnClickListener(){public void onClick(View v){exit(streamUrl);}});
    vlc.setTextColor(TEAL);
    spaced(utilities,vlc,90,42,5);
    bottomPanel.addView(utilities);
    root.addView(bottomPanel,new FrameLayout.LayoutParams(-1,dp(136),Gravity.BOTTOM));

    status=text("Connecting to video…",13);
    status.setBackgroundColor(0xa6111724);
    FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,dp(36),Gravity.CENTER);
    root.addView(status,sp);
    setContentView(root);
    updateAspect();
    openedAt=System.currentTimeMillis();

    try{
      DefaultRenderersFactory renderers=new DefaultRenderersFactory(this);
      // Load bundled FFmpeg audio renderer after the native hardware renderer:
      // AC3/EAC3/DTS/TrueHD use FFmpeg when the head unit has no working decoder.
      // Supported AAC/MP3 retain the faster platform output where possible.
      renderers.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
      renderers.setEnableDecoderFallback(true);
      selector=new DefaultTrackSelector(this);
      // Prefer English audio automatically when the stream supplies it,
      // while choosing AAC over unsupported AC3/DTS when possible.
      // Users can still override this in Audio / Language for each video.
      selector.setParameters(selector.buildUponParameters()
        .setPreferredAudioLanguage("en")
        .setPreferredAudioMimeType("audio/mp4a-latm"));
      player=new SimpleExoPlayer.Builder(this,renderers).setTrackSelector(selector).build();
      player.setAudioAttributes(new AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA).setContentType(C.CONTENT_TYPE_MOVIE).build(),true);
      player.setVolume(1.0f);
      video.setPlayer(player);
      player.addVideoListener(new VideoListener(){
        @Override public void onRenderedFirstFrame(){
          firstFrame=true;clearStatus();scheduleHide();
        }
      });
      player.addListener(new Player.EventListener(){
        @Override public void onPlayerStateChanged(boolean ready,int state){
          if(released)return;
          if(state==Player.STATE_READY){
            if(!autoplayStarted&&!userPaused){autoplayStarted=true;player.setPlayWhenReady(true);}
            if(firstFrame)clearStatus();
            scheduleHide();
          }else if(state==Player.STATE_BUFFERING && !firstFrame)status("Buffering video…");
          else if(state==Player.STATE_ENDED){
            showControls(true);status("Playback finished. Tap Sources to return.");
          }
          updateAudioTrackInfo();
        }
        @Override public void onPlayerError(PlaybackException error){
          if(released)return;
          Throwable why=error.getCause();
          String type=why==null?error.getClass().getSimpleName():why.getClass().getSimpleName();
          status("Cannot play this format ("+type+"). Try another source or Open VLC.");
          showControls(true);
        }
      });
      // Reuse the verified modern CA roots. Normal HTTPS hostname validation stays ON.
      originalTls=HttpsURLConnection.getDefaultSSLSocketFactory();
      HttpsURLConnection.setDefaultSSLSocketFactory(VideoTls.build(this));
      DefaultDataSourceFactory data=new DefaultDataSourceFactory(
        this,Util.getUserAgent(this,"StremGen5W"));
      Uri uri=Uri.parse(streamUrl);
      String file=uri.getPath();
      String path=file==null?"":file.toLowerCase(Locale.US);
      MediaSource media;
      if(path.endsWith(".m3u8"))media=new HlsMediaSource.Factory(data).createMediaSource(uri);
      else if(path.endsWith(".mpd"))media=new DashMediaSource.Factory(data).createMediaSource(uri);
      else media=new ProgressiveMediaSource.Factory(data).createMediaSource(uri);
      player.setPlayWhenReady(true);
      player.prepare(media);
      handler.postDelayed(progressRunnable,500);
      showControls(true);
    }catch(Throwable failure){
      status("Playback setup failed: "+failure.getClass().getSimpleName()+". Try VLC.");
      showControls(true);
    }
  }

  static class TrackChoice {
    final int renderer,group,track;
    final boolean supported;
    final String name;
    TrackChoice(int r,int g,int t,boolean supported,String n){
      renderer=r;group=g;track=t;this.supported=supported;name=n;
    }
  }
  private String nameTrack(Format format,int kind){
    StringBuilder label=new StringBuilder();
    if(format.label!=null&&!format.label.isEmpty())label.append(format.label).append(" • ");
    if(format.language!=null&&!format.language.isEmpty()&&!format.language.equals("und"))
      label.append(format.language.toUpperCase(Locale.US)).append(" • ");
    if(format.sampleMimeType!=null){
      String codec=format.sampleMimeType;
      if(codec.equals("audio/mp4a-latm"))codec="AAC";
      else if(codec.equals("audio/ac3"))codec="AC3";
      else if(codec.equals("audio/eac3"))codec="E-AC3";
      else if(codec.equals("audio/vnd.dts"))codec="DTS";
      else if(codec.equals("audio/mpeg"))codec="MP3";
      else if(codec.equals("audio/opus"))codec="Opus";
      label.append(codec);
    }else label.append(kind==C.TRACK_TYPE_AUDIO?"Audio":"Subtitle");
    if(kind==C.TRACK_TYPE_AUDIO && format.channelCount>0)
      label.append(" • ").append(format.channelCount).append(" channels");
    return label.toString();
  }
  // A dark, touch-friendly track drawer replaces the tiny Holo list popup.
  // It contains real renderer-supported streams and a media volume slider.
  private void playerNotice(String heading,String message){
    final Dialog modal=new Dialog(this);
    modal.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout body=dialogShell(heading,"STREMGEN5W  •  PLAYBACK");
    TextView note=text(message,15);
    note.setTextColor(MUTED);note.setPadding(dp(14),dp(12),dp(14),dp(17));
    body.addView(note);
    Button close=tool("Close",new View.OnClickListener(){public void onClick(View v){modal.dismiss();}});
    LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(47));
    p.setMargins(dp(9),0,dp(9),dp(10));body.addView(close,p);
    modal.setContentView(body);
    presentModal(modal);
  }
  private LinearLayout dialogShell(String heading,String eyebrow){
    LinearLayout shell=new LinearLayout(this);
    shell.setOrientation(1);shell.setPadding(dp(13),dp(14),dp(13),dp(12));
    shell.setBackgroundDrawable(pill(PANEL_BASE,BORDER,17));
    TextView title=text(heading,21);
    title.setTypeface(null,1);title.setPadding(dp(13),dp(3),dp(10),dp(1));
    shell.addView(title);
    TextView sub=small(eyebrow);
    sub.setTextColor(TEAL);sub.setPadding(dp(14),dp(2),dp(12),dp(11));
    shell.addView(sub);
    return shell;
  }
  private int playerWindowHeight(){
    return root!=null&&root.getHeight()>0?root.getHeight():getResources().getDisplayMetrics().heightPixels;
  }
  private void presentModal(Dialog modal){
    presentModal(modal,0);
  }
  // Track menus receive a fixed, viewport-relative window height.
  // The track list is the only vertically scrollable part, so its footer stays visible.
  private void presentModal(Dialog modal,int requestedHeight){
    modal.setOnDismissListener(new android.content.DialogInterface.OnDismissListener(){
      public void onDismiss(android.content.DialogInterface d){showControls(true);}
    });
    modal.show();
    Window window=modal.getWindow();
    if(window!=null){
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      WindowManager.LayoutParams lp=window.getAttributes();
      lp.gravity=Gravity.CENTER;
      int width=root!=null&&root.getWidth()>0?root.getWidth():getResources().getDisplayMetrics().widthPixels;
      int height=playerWindowHeight();
      lp.width=Math.max(1,Math.min(width-dp(32),dp(680)));
      lp.height=requestedHeight>0?Math.max(1,Math.min(requestedHeight,height-dp(24))):
        WindowManager.LayoutParams.WRAP_CONTENT;
      lp.dimAmount=0.65f;
      window.setAttributes(lp);
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }
  }
  private void dismissTrackOverlay(FrameLayout overlay){
    if(overlay==null)return;
    if(overlay.getParent() instanceof android.view.ViewGroup)
      ((android.view.ViewGroup)overlay.getParent()).removeView(overlay);
    if(activeTrackOverlay==overlay)activeTrackOverlay=null;
    showControls(true);
  }
  private void chooseTracks(final int kind){
    showControls(true);handler.removeCallbacks(hideRunnable);
    if(selector==null){
      playerNotice("Player unavailable","The video player has not initialized yet.");
      return;
    }
    final MappedTrackInfo map=selector.getCurrentMappedTrackInfo();
    if(map==null){
      playerNotice("Loading tracks","Wait until the video begins playing, then open this menu again.");
      return;
    }
    final ArrayList<TrackChoice> choices=new ArrayList<TrackChoice>();
    if(kind==C.TRACK_TYPE_AUDIO){
      choices.add(null); // automatic English preference
    }else{
      choices.add(null); // subtitles off
      choices.add(new TrackChoice(-1,-1,-1,true,"Automatic"));
    }
    for(int r=0;r<map.getRendererCount();r++){
      if(map.getRendererType(r)!=kind)continue;
      TrackGroupArray groups=map.getTrackGroups(r);
      for(int g=0;g<groups.length;g++){
        TrackGroup group=groups.get(g);
        for(int i=0;i<group.length;i++){
          Format format=group.getFormat(i);
          boolean supported=map.getTrackSupport(r,g,i)==C.FORMAT_HANDLED;
          choices.add(new TrackChoice(r,g,i,supported,nameTrack(format,kind)));
        }
      }
    }
    final boolean audio=kind==C.TRACK_TYPE_AUDIO;
    // In-player overlay avoids vendor/Holo system-dialog positioning and clipping.
    if(activeTrackOverlay!=null)dismissTrackOverlay(activeTrackOverlay);
    final FrameLayout overlay=new FrameLayout(this);
    overlay.setBackgroundColor(0xc6000000);
    overlay.setClickable(true);
    overlay.setFocusable(true);
    LinearLayout panel=dialogShell(audio?"Audio & Language":"Subtitles & Captions",
      audio?"PREFER ENGLISH  •  CHOOSE A SOUNDTRACK":"LANGUAGE  •  ON / OFF");

    if(audio){
      LinearLayout volumeRow=new LinearLayout(this);
      volumeRow.setOrientation(0);volumeRow.setGravity(Gravity.CENTER_VERTICAL);
      volumeRow.setPadding(dp(13),dp(5),dp(14),dp(8));
      TextView volumeLabel=text("♫  Volume",15);
      volumeRow.addView(volumeLabel,new LinearLayout.LayoutParams(dp(132),dp(39)));
      SeekBar level=new SeekBar(this);
      int max=audioManager==null?15:Math.max(1,audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
      int current=audioManager==null?max/2:audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
      level.setMax(max);level.setProgress(current);
      level.setContentDescription("Media volume");
      if(level.getProgressDrawable()!=null)level.getProgressDrawable().setColorFilter(TEAL,android.graphics.PorterDuff.Mode.SRC_IN);
      if(level.getThumb()!=null)level.getThumb().setColorFilter(TEAL,android.graphics.PorterDuff.Mode.SRC_IN);
      level.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
        public void onProgressChanged(SeekBar bar,int value,boolean user){
          if(user&&audioManager!=null)audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);
        }
        public void onStartTrackingTouch(SeekBar bar){}
        public void onStopTrackingTouch(SeekBar bar){}
      });
      volumeRow.addView(level,new LinearLayout.LayoutParams(0,dp(44),1));
      panel.addView(volumeRow);
      // Language preference is already stated in the fixed dialog header.
    }else{
      // Track list itself supplies the Off and Automatic choices.
    }

    final ScrollView scroll=new ScrollView(this);
    scroll.setFillViewport(false);
    scroll.setVerticalScrollBarEnabled(true);
    scroll.setScrollbarFadingEnabled(false);
    scroll.setSmoothScrollingEnabled(true);
    LinearLayout rows=new LinearLayout(this);rows.setOrientation(1);
    int actual=audio?choices.size()-1:choices.size()-2;
    if(actual==0){
      TextView empty=small(audio?
        "This source has no selectable audio tracks. Try a different source or VLC if audio is silent.":
        "No selectable subtitle tracks were provided. Text burned into the picture cannot be switched off.");
      empty.setPadding(dp(15),dp(14),dp(15),dp(15));
      rows.addView(empty);
    }
    for(int index=0;index<choices.size();index++){
      final TrackChoice choice=choices.get(index);
      final String label=choice==null?(audio?"Automatic  •  English preferred":"Off  •  No subtitles"):
        choice.renderer==-1?"Automatic  •  Source default":choice.name;
      boolean enabled=choice==null||choice.supported;
      final String presented=label+(enabled?"":"  •  Decoder unavailable");
      TextView item=text(presented,15);
      item.setTextColor(enabled?WHITE:0xff85949e);
      item.setPadding(dp(14),dp(5),dp(14),dp(5));
      item.setBackgroundDrawable(pill(index==0?0xaa1b5556:0xff1d2a37,
        index==0?0xff3b9891:0xff344453,10));
      item.setGravity(Gravity.CENTER_VERTICAL);
      item.setMinHeight(dp(43));item.setClickable(true);
      LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,-2);
      ip.setMargins(dp(7),dp(2),dp(7),dp(4));
      rows.addView(item,ip);
      item.setOnClickListener(new View.OnClickListener(){public void onClick(View view){
        if(choice!=null&&!choice.supported){
          playerNotice("Audio format unavailable",
            "This track cannot be decoded by the available player codecs. Try another audio track or choose a different source.");
          return;
        }
        DefaultTrackSelector.ParametersBuilder settings=selector.buildUponParameters();
        if(choice==null || choice.renderer==-1){
          for(int r=0;r<map.getRendererCount();r++){
            if(map.getRendererType(r)==kind){
              settings.setRendererDisabled(r,kind==C.TRACK_TYPE_TEXT&&choice==null);
              settings.clearSelectionOverrides(r);
            }
          }
          if(audio){
            settings.setPreferredAudioLanguage("en");
            settings.setPreferredAudioMimeType("audio/mp4a-latm");
          }
          if(!audio&&subtitleButton!=null)subtitleButton.setText(
            choice==null?"CC  Off":"CC  Subtitles");
        }else{
          settings.setRendererDisabled(choice.renderer,false);
          settings.setSelectionOverride(choice.renderer,map.getTrackGroups(choice.renderer),
            new DefaultTrackSelector.SelectionOverride(choice.group,choice.track));
          if(!audio&&subtitleButton!=null)subtitleButton.setText("CC  Subtitles");
        }
        selector.setParameters(settings);
        dismissTrackOverlay(overlay);
      }});
    }
    scroll.addView(rows);
    // Flexible scroll region. On the head unit the old fixed-height rows pushed
    // the Done button below the dialog's visible bottom edge.
    panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));
    Button close=tool("Done",new View.OnClickListener(){public void onClick(View v){dismissTrackOverlay(overlay);}});
    close.setTextSize(15);
    close.setMinHeight(0);
    LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(43));
    cp.setMargins(dp(7),dp(5),dp(7),dp(2));
    panel.addView(close,cp);
    // Use the actual player viewport, not displayMetrics, so fullscreen and
    // vendor system-bar quirks never allow the menu to overrun the screen.
    int viewportHeight=playerWindowHeight();
    int dialogHeight=Math.max(1,Math.min((int)(viewportHeight*0.78f),viewportHeight-dp(26)));
    panel.setPadding(dp(10),dp(6),dp(10),dp(6));
    TextView heading=(TextView)panel.getChildAt(0);
    heading.setTextSize(18);
    heading.setPadding(dp(9),dp(2),dp(9),0);
    TextView eyebrow=(TextView)panel.getChildAt(1);
    eyebrow.setTextSize(11);
    eyebrow.setPadding(dp(10),0,dp(9),dp(3));
    // The panel is drawn INSIDE the actual ExoPlayer root, not a separate
    // Android dialog window. No status/navigation bar can push Done offscreen.
    int viewportWidth=root.getWidth()>0?root.getWidth():getResources().getDisplayMetrics().widthPixels;
    int panelWidth=Math.max(dp(260),Math.min(viewportWidth-dp(44),dp(650)));
    FrameLayout.LayoutParams popup=new FrameLayout.LayoutParams(
      panelWidth,dialogHeight,Gravity.CENTER);
    overlay.addView(panel,popup);
    root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
    activeTrackOverlay=overlay;
  }
  private void updateAudioTrackInfo(){
    if(selector==null || trackMessageShown)return;
    MappedTrackInfo map=selector.getCurrentMappedTrackInfo();if(map==null)return;
    int total=0,supported=0;
    for(int r=0;r<map.getRendererCount();r++){
      if(map.getRendererType(r)!=C.TRACK_TYPE_AUDIO)continue;
      TrackGroupArray groups=map.getTrackGroups(r);
      for(int g=0;g<groups.length;g++)for(int i=0;i<groups.get(g).length;i++){
        total++;if(map.getTrackSupport(r,g,i)==C.FORMAT_HANDLED)supported++;
      }
    }
    if(total>0){
      trackMessageShown=true;
      if(supported==0){
        status("This stream has no supported audio tracks. Choose Audio for details or use a source with AAC.");
        showControls(true);
      }else if(audioButton!=null)audioButton.setText("♫  Audio");
    }
  }

  private void exit(String fallback){
    if(released)return;
    released=true;handler.removeCallbacks(hideRunnable);handler.removeCallbacks(progressRunnable);
    try{
      if(player!=null){
        long p=player.getCurrentPosition(),d=player.getDuration();
        if(p>=0)lastPosition=p;
        if(d>0&&d!=C.TIME_UNSET)lastDuration=d;
        player.setPlayWhenReady(false);player.stop();player.release();player=null;
      }
      if(video!=null)video.setPlayer(null);
    }catch(Throwable ignored){}
    Intent response=new Intent();
    response.putExtra("extra_position",lastPosition);
    response.putExtra("extra_duration",lastDuration);
    if(fallback!=null)response.putExtra("fallback_vlc_url",fallback);
    setResult(RESULT_OK,response);
    restoreBrightness();
    finish();
  }
  @Override public void onBackPressed(){if(activeTrackOverlay!=null){dismissTrackOverlay(activeTrackOverlay);return;}exit(null);}
  @Override public boolean dispatchTouchEvent(MotionEvent event){
    if(event.getAction()==MotionEvent.ACTION_DOWN){fingerX=event.getX();fingerY=event.getY();}
    // A timeline drag must NEVER be interpreted as a swipe-to-exit, even
    // if the finger passes through Android's gesture strip.
    boolean overTimeline=fingerY>getResources().getDisplayMetrics().heightPixels-dp(175);
    if(event.getAction()==MotionEvent.ACTION_UP && !seeking && !overTimeline &&
      fingerX<dp(46) && event.getX()-fingerX>dp(110) &&
      Math.abs(event.getY()-fingerY)<dp(105)){
      exit(null);return true;
    }
    return super.dispatchTouchEvent(event);
  }
  @Override protected void onPause(){
    super.onPause();
    if(player!=null&&!released){
      player.setPlayWhenReady(false);showControls(true);
    }
  }
  @Override protected void onDestroy(){
    restoreBrightness();
    handler.removeCallbacks(hideRunnable);handler.removeCallbacks(progressRunnable);
    if(!released&&player!=null)try{player.release();}catch(Throwable ignored){}
    player=null;released=true;
    if(originalTls!=null){
      HttpsURLConnection.setDefaultSSLSocketFactory(originalTls);originalTls=null;
    }
    if(audioManager!=null&&audioFocusGranted)audioManager.abandonAudioFocus(focusListener);
    super.onDestroy();
  }
}
