package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.RetryableIntegrationFailure;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** Fixed destinations only, no redirects, bounded responses; replace this transport with mocks in tests. */
public class ProviderHttp {
    public record Result(int status,String body) { @Override public String toString(){return "ProviderResult[redacted]";} }
    public Result exchange(String method,URI uri,Map<String,String> headers,String body) {
        if(!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || uri.getPort()!=-1 ||
                !Set.of("api.meshy.ai","oauth2.googleapis.com").contains(uri.getHost())) throw AccountDeletionException.integration();
        HttpURLConnection c=null;
        try{
            c=(HttpURLConnection)uri.toURL().openConnection();
            c.setRequestMethod(method);c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(20000);
            for(var header:headers.entrySet())c.setRequestProperty(header.getKey(),header.getValue());
            if(body!=null){c.setDoOutput(true);try(var out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();
            InputStream source=status>=400?c.getErrorStream():c.getInputStream();
            String response="";
            if(source!=null)try(source){
                byte[] bytes=source.readNBytes(1024*1024+1);
                if(bytes.length>1024*1024)throw AccountDeletionException.integration();
                response=new String(bytes,StandardCharsets.UTF_8);
            }
            return new Result(status,response);
        }catch(IOException e){throw new RetryableIntegrationFailure();}
        finally{if(c!=null)c.disconnect();}
    }
    public static void requireSuccess(int status) {
        if(status==408 || status==409 || status==429 || status>=500)throw new RetryableIntegrationFailure();
        if(status!=200 && status!=204)throw AccountDeletionException.integration();
    }
}
