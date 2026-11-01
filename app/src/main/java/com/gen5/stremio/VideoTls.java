package com.gen5.stremio;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import javax.net.ssl.SSLSocketFactory;

/**
 * Secure Android 4.4 HTTPS for media playback.
 *
 * Uses the identical bundled Mozilla trust roots that the confirmed-working
 * artwork downloader uses. Does not disable any TLS or hostname validation.
 * Built for ExoPlayer's OkHttp extension only; no device-wide changes.
 */
final class VideoTls {
  private VideoTls() {}

  private static SSLSocketFactory cached;
  private static SSLSocketFactory sportsCached;
  private static X509TrustManager cachedRoots;
  private static String sportsStatus="Not initialized";
  static synchronized String sportsTransportStatus(){return sportsStatus;}
  // Uses a modern, app-bundled TLS provider for servers that reject
  // Android 4.4's obsolete cipher suite. Trust and hostname checks remain on.
  static synchronized SSLSocketFactory buildSports(Context context)throws Exception{
    if(sportsCached!=null)return sportsCached;
    SSLSocketFactory fallback=build(context);
    try{
      if(!org.conscrypt.Conscrypt.isAvailable()){
        sportsStatus="Modern TLS provider unavailable; Android TLS fallback";
        return fallback;
      }
      SSLContext modern=SSLContext.getInstance("TLS",org.conscrypt.Conscrypt.newProvider());
      modern.init(null,new TrustManager[]{cachedRoots},new SecureRandom());
      sportsCached=new MainActivity.Tls12Factory(modern.getSocketFactory());
      sportsStatus="Modern Conscrypt TLS with verified Mozilla roots";
      return sportsCached;
    }catch(Throwable ex){
      sportsStatus="Modern TLS unavailable ("+ex.getClass().getSimpleName()+"); secure fallback";
      return fallback;
    }
  }
  static synchronized SSLSocketFactory build(Context context) throws Exception {
    if(cached!=null)return cached;
    KeyStore roots=KeyStore.getInstance(KeyStore.getDefaultType());
    roots.load(null,null);
    InputStream in=context.getAssets().open("cacert.pem");
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    try{
      byte[] chunk=new byte[8192];int read;
      while((read=in.read(chunk))!=-1){
        out.write(chunk,0,read);
        if(out.size()>600000)throw new IOException("Certificate bundle too large");
      }
    }finally{in.close();}
    String pem=new String(out.toByteArray(),"US-ASCII");
    String start="-----BEGIN CERTIFICATE-----";
    String finish="-----END CERTIFICATE-----";
    CertificateFactory cf=CertificateFactory.getInstance("X.509");
    int index=0,count=0;
    while(true){
      int first=pem.indexOf(start,index);if(first<0)break;
      int last=pem.indexOf(finish,first);if(last<0)break;
      String body=pem.substring(first+start.length(),last).replaceAll("\\s+","");
      byte[] der=Base64.decode(body,Base64.DEFAULT);
      Certificate certificate=cf.generateCertificate(new ByteArrayInputStream(der));
      roots.setCertificateEntry("mozilla-"+count,certificate);
      count++;index=last+finish.length();
    }
    if(count<80)throw new IOException("Too few trusted Mozilla certificates");
    TrustManagerFactory tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(roots);
    X509TrustManager manager=null;
    for(TrustManager tm:tmf.getTrustManagers()){
      if(tm instanceof X509TrustManager){manager=(X509TrustManager)tm;break;}
    }
    if(manager==null)throw new IOException("X509 trust manager unavailable");
    cachedRoots=manager;
    SSLContext ssl=SSLContext.getInstance("TLS");
    ssl.init(null,new TrustManager[]{manager},new SecureRandom());
    // Force TLS 1.2, as in the working artwork downloader, without disabling
    // certificate or hostname validation. Scope the default to this app process.
    cached=new MainActivity.Tls12Factory(ssl.getSocketFactory());
    return cached;
  }
}
