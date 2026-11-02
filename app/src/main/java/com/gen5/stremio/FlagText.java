package com.gen5.stremio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ImageSpan;
import android.util.LruCache;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline flag-emoji rendering for the Android 4.4 head unit.
 * KitKat doesn't reliably render regional-indicator pairs as country flags.
 * Preserve the source text, replacing supported flags with locally bundled
 * Twemoji graphics. Unsupported countries keep their original text.
 */
public final class FlagText {
  private static final String COUNTRIES=" us gb ca au nz ie es mx ar cl co pe br pt fr be de at ch it nl lu se no dk fi is pl cz sk hu ro bg hr rs si gr tr ru ua lt lv ee jp kr cn tw hk in pk bd lk np th vn id my sg ph kh sa ae il ir eg ma za ng ke ";
  // Only explicit language tags are promoted; don't replace arbitrary lowercase
  // words such as "in", "is" and "no" with country flags.
  private static final Pattern LANGUAGE_TAG=Pattern.compile(
    "(?<![A-Za-z0-9])(?:[A-Z]{2,3}|Fr|Es|De|Pt|Ru|Nl)(?![A-Za-z0-9])");
  private static final LruCache<String,Drawable> CACHE=new LruCache<String,Drawable>(74);
  private FlagText(){}

  private static String country(String tag){
    String code=tag.toLowerCase(Locale.US);
    if("en".equals(code)||"eng".equals(code))return "gb";
    if("spa".equals(code))return "es";
    if("fra".equals(code)||"fre".equals(code))return "fr";
    if("deu".equals(code)||"ger".equals(code))return "de";
    if("ita".equals(code))return "it";
    if("por".equals(code))return "pt";
    if("rus".equals(code))return "ru";
    if("jpn".equals(code)||"ja".equals(code))return "jp";
    if("kor".equals(code)||"ko".equals(code))return "kr";
    if("zho".equals(code)||"chi".equals(code)||"zh".equals(code))return "cn";
    if("sv".equals(code))return "se";
    if("da".equals(code))return "dk";
    if("nb".equals(code)||"nn".equals(code))return "no";
    if("uk".equals(code))return "ua";
    if("cs".equals(code))return "cz";
    if("el".equals(code))return "gr";
    if("vi".equals(code))return "vn";
    if("he".equals(code)||"iw".equals(code))return "il";
    if("hi".equals(code)||"ta".equals(code)||"te".equals(code))return "in";
    if("ar".equals(code))return "sa";
    if("fa".equals(code))return "ir";
    if("ur".equals(code))return "pk";
    if("bn".equals(code))return "bd";
    if("ne".equals(code))return "np";
    if("si".equals(code))return "lk";
    if("km".equals(code))return "kh";
    if("fil".equals(code)||"tl".equals(code))return "ph";
    if("ms".equals(code))return "my";
    if("af".equals(code))return "za";
    if("sw".equals(code))return "ke";
    if("am".equals(code))return "ee";
    if("ca".equals(code)||"eu".equals(code)||"gl".equals(code))return "es";
    return COUNTRIES.contains(" "+code+" ")?code:null;
  }

  private static Drawable image(Context context,String country){
    Drawable cached=CACHE.get(country);
    if(cached!=null)return cached;
    InputStream input=null;
    try{
      input=context.getAssets().open("flags/"+country+".png");
      BitmapFactory.Options options=new BitmapFactory.Options();
      options.inSampleSize=2;
      Bitmap bitmap=BitmapFactory.decodeStream(input,null,options);
      if(bitmap==null)return null;
      BitmapDrawable drawable=new BitmapDrawable(context.getResources(),bitmap);
      int size=(int)(22f*context.getResources().getDisplayMetrics().density+0.5f);
      drawable.setBounds(0,0,size,size);
      CACHE.put(country,drawable);
      return drawable;
    }catch(Exception missing){return null;}
    finally{if(input!=null)try{input.close();}catch(Exception ignored){}}
  }

  private static String flagAt(String text,int pos){
    if(pos+4>text.length())return null;
    int a=Character.codePointAt(text,pos);
    if(a<0x1f1e6||a>0x1f1ff)return null;
    int next=pos+Character.charCount(a);
    if(next>=text.length())return null;
    int b=Character.codePointAt(text,next);
    if(b<0x1f1e6||b>0x1f1ff)return null;
    return new String(new char[]{(char)('a'+a-0x1f1e6),(char)('a'+b-0x1f1e6)});
  }

  /** Extract recognizable language indicators for prominent display in tags. */
  public static String indicators(String source){
    if(source==null||source.length()==0)return "";
    StringBuilder found=new StringBuilder();
    HashSet<String> used=new HashSet<String>();
    for(int i=0;i<source.length();){
      String code=flagAt(source,i);
      if(code!=null){
        if(COUNTRIES.contains(" "+code+" ")&&used.add(code)){
          if(found.length()>0)found.append("  ");
          found.append(source.substring(i,i+4));
        }
        i+=4;
      }else i+=Character.charCount(Character.codePointAt(source,i));
    }
    Matcher matcher=LANGUAGE_TAG.matcher(source);
    while(matcher.find()){
      String code=country(matcher.group());
      if(code!=null&&used.add(code)){
        if(found.length()>0)found.append("  ");
        found.append(matcher.group());
      }
    }
    return found.toString();
  }

  public static CharSequence decorate(Context context,String source){
    if(source==null||source.length()==0)return source==null?"":source;
    SpannableString output=new SpannableString(source);
    for(int i=0;i<source.length();){
      String code=flagAt(source,i);
      if(code!=null){
        Drawable d=image(context,code);
        if(d!=null)output.setSpan(new ImageSpan(d,ImageSpan.ALIGN_BOTTOM),
          i,i+4,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        i+=4;
      }else i+=Character.charCount(Character.codePointAt(source,i));
    }
    Matcher matcher=LANGUAGE_TAG.matcher(source);
    while(matcher.find()){
      String code=country(matcher.group());
      Drawable d=code==null?null:image(context,code);
      if(d!=null)output.setSpan(new ImageSpan(d,ImageSpan.ALIGN_BOTTOM),
        matcher.start(),matcher.end(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    return output;
  }
}
